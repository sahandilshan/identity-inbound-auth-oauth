/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.wso2.carbon.identity.oauth2;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.oauth.cache.OAuthScopeCache;
import org.wso2.carbon.identity.oauth.cache.OAuthScopeCacheKey;
import org.wso2.carbon.identity.oauth2.bean.Scope;
import org.wso2.carbon.identity.oauth2.dao.OAuthTokenPersistenceFactory;
import org.wso2.carbon.identity.oauth2.model.OAuth2ScopeConsentResponse;
import org.wso2.carbon.identity.oauth2.model.UserApplicationScopeConsentDO;
import org.wso2.carbon.identity.oauth2.util.Oauth2ScopeUtils;
import org.wso2.carbon.identity.openidconnect.cache.OIDCScopeClaimCache;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.
        ERROR_CODE_BAD_REQUEST_SCOPE_NAME_NOT_SATIFIED_THE_REGEX;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_ADD_USER_CONSENT_FOR_APP;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_CHECK_ALREADY_USER_CONSENTED;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_CHECK_EXISTING_CONSENTS_FOR_USER;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_RETRIEVE_USER_CONSENTS;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_RETRIEVE_USER_CONSENTS_FOR_APP;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_REVOKE_USER_CONSENT;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_REVOKE_USER_CONSENT_FOR_APP;
import static org.wso2.carbon.identity.oauth2.Oauth2ScopeConstants.ErrorMessages.ERROR_CODE_FAILED_TO_UPDATE_USER_CONSENT_FOR_APP;

/**
 * OAuth2ScopeService use for scope handling
 */
public class OAuth2ScopeService {
    private static final Log log = LogFactory.getLog(OAuth2ScopeService.class);
    private static final String SCOPE_VALIDATION_REGEX = "^[^?#/()]*$";

    /**
     * Register a scope with the bindings
     *
     * @param scope details of the scope to be registered
     * @throws IdentityOAuth2ScopeServerException
     */
    public Scope registerScope(Scope scope) throws IdentityOAuth2ScopeException {

        addScopePreValidation(scope);

        // Check whether a scope exists with the provided scope name or not regardless of scope type. We don't allow
        // to register same scope name across OAuth2 and OIDC scope endpoints. We keep the scope name as unique.
        boolean isScopeExists = isScopeExists(scope.getName(), true);
        if (isScopeExists) {
            // Rechecking to see if the existing scope is an OIDC scope to improve error response.
            if (isScopeExists(scope.getName(), false)) {
                throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                        ERROR_CODE_CONFLICT_REQUEST_EXISTING_SCOPE, scope.getName());
            } else {
                throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                        ERROR_CODE_CONFLICT_REQUEST_EXISTING_SCOPE_OIDC, scope.getName());
            }
        }

        int tenantID = Oauth2ScopeUtils.getTenantID();
        try {
            OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO().addScope(scope, tenantID);
            if (log.isDebugEnabled()) {
                log.debug("Scope is added to the database. \n" + scope.toString());
            }
        } catch (IdentityOAuth2ScopeServerException e) {
            throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_FAILED_TO_REGISTER_SCOPE, scope.toString(), e);
        }

        OAuthScopeCache.getInstance().addToCache(new OAuthScopeCacheKey(scope.getName(), Integer.toString(tenantID)),
                scope);
        return scope;
    }

    /**
     * Retrieve the available scope list
     *
     * @param startIndex Start Index of the result set to enforce pagination
     * @param count      Number of elements in the result set to enforce pagination
     * @return Scope list
     * @throws IdentityOAuth2ScopeServerException
     * @deprecated use {@link #getScopes(Integer, Integer, Boolean, String)} instead.
     */
    public Set<Scope> getScopes(Integer startIndex, Integer count)
            throws IdentityOAuth2ScopeServerException {

        return getScopes(startIndex, count, false, null);
    }

    /**
     * Retrieve the available scope list.
     *
     * @param startIndex        Start Index of the result set to enforce pagination.
     * @param count             Number of elements in the result set to enforce pagination.
     * @param includeOIDCScopes Include OIDC scopes as well.
     * @param requestedScopes   Requested set of scopes to be return in the response.
     * @return Scope list.
     * @throws IdentityOAuth2ScopeServerException
     */
    public Set<Scope> getScopes(Integer startIndex, Integer count, Boolean includeOIDCScopes, String requestedScopes)
            throws IdentityOAuth2ScopeServerException {

        Set<Scope> scopes;

        // includeOIDCScopes can be null.
        boolean includeOIDCScopesState = BooleanUtils.isTrue(includeOIDCScopes);

        // If the requested scopes are provided we won't honour pagination. Will return requested scopes only.
        if (StringUtils.isNotBlank(requestedScopes)) {
            try {
                scopes = OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO()
                        .getRequestedScopesOnly(Oauth2ScopeUtils.getTenantID(), includeOIDCScopesState,
                                requestedScopes);
            } catch (IdentityOAuth2ScopeServerException e) {
                throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                        ERROR_CODE_FAILED_TO_GET_REQUESTED_SCOPES, e);
            }
        } else {
            // Check for pagination query params.
            if (startIndex == null && count == null) {
                try {
                    scopes = OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO()
                            .getAllScopes(Oauth2ScopeUtils.getTenantID(), includeOIDCScopesState);
                } catch (IdentityOAuth2ScopeServerException e) {
                    throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                            ERROR_CODE_FAILED_TO_GET_ALL_SCOPES, e);
                }
            } else {
                // Check if it is a pagination request.
                scopes = listScopesWithPagination(startIndex, count, includeOIDCScopesState);
            }
        }
        return scopes;
    }

    /**
     * @param name Name of the scope which need to get retrieved
     * @return Retrieved Scope
     * @throws IdentityOAuth2ScopeException
     */
    public Scope getScope(String name) throws IdentityOAuth2ScopeException {

        Scope scope;
        int tenantID = Oauth2ScopeUtils.getTenantID();

        validateScopeName(name);

        scope = OAuthScopeCache.getInstance().getValueFromCache(new OAuthScopeCacheKey(name,
                Integer.toString(tenantID)));

        if (scope == null) {
            try {
                scope = OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO().getScopeByName(name, tenantID);
                if (scope != null) {
                    if (log.isDebugEnabled()) {
                        log.debug("Scope is getting from the database. \n" + scope.toString());
                    }
                    OAuthScopeCache.getInstance().addToCache(new OAuthScopeCacheKey(name, Integer.toString(tenantID))
                            , scope);
                }

            } catch (IdentityOAuth2ScopeServerException e) {
                throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                        ERROR_CODE_FAILED_TO_GET_SCOPE_BY_NAME, name, e);
            }
        }

        if (scope == null) {
            throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_NOT_FOUND_SCOPE, name);
        }

        return scope;
    }

    /**
     * Check the existence of a scope
     *
     * @param name Name of the scope
     * @return true if scope with the given scope name exists
     * @throws IdentityOAuth2ScopeException
     */
    public boolean isScopeExists(String name) throws IdentityOAuth2ScopeException {

        boolean isScopeExists;
        int tenantID = Oauth2ScopeUtils.getTenantID();

        if (name == null) {
            throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_BAD_REQUEST_SCOPE_NAME_NOT_SPECIFIED, null);
        }

        Scope scopeFromCache = OAuthScopeCache.getInstance()
                .getValueFromCache(new OAuthScopeCacheKey(name, Integer.toString(tenantID)));

        if (scopeFromCache != null) {
            isScopeExists = true;
        } else {
            try {
                isScopeExists = OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO().isScopeExists(name,
                        tenantID);
            } catch (IdentityOAuth2ScopeServerException e) {
                throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                        ERROR_CODE_FAILED_TO_GET_SCOPE_BY_NAME, name, e);
            }
        }

        return isScopeExists;
    }

    /**
     * Check the existence of a scope depends on scope type. Type can be OAUTH2 scopes or OIDC scopes.
     *
     * @param name              Name of the scope.
     * @param includeOIDCScopes Include OIDC scopes as well.
     * @return True if scope with the given scope name exists.
     * @throws IdentityOAuth2ScopeException
     */
    public boolean isScopeExists(String name, boolean includeOIDCScopes) throws IdentityOAuth2ScopeException {

        boolean isScopeExists;
        int tenantID = Oauth2ScopeUtils.getTenantID();

        if (name == null) {
            throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_BAD_REQUEST_SCOPE_NAME_NOT_SPECIFIED, null);
        }

        Scope scopeFromCache = OAuthScopeCache.getInstance()
                .getValueFromCache(new OAuthScopeCacheKey(name, Integer.toString(tenantID)));

        if (scopeFromCache != null) {
            isScopeExists = true;
        } else {
            try {
                isScopeExists = OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO().isScopeExists(name,
                        tenantID, includeOIDCScopes);
            } catch (IdentityOAuth2ScopeServerException e) {
                throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                        ERROR_CODE_FAILED_TO_GET_SCOPE_BY_NAME, name, e);
            }
        }

        return isScopeExists;
    }

    /**
     * Delete the scope for the given scope ID
     *
     * @param name Scope ID of the scope which need to get deleted
     * @throws IdentityOAuth2ScopeException
     */
    public void deleteScope(String name) throws IdentityOAuth2ScopeException {

        validateScopeName(name);
        // Check whether a scope exists with the provided scope name which to be deleted.
        validateScopeExistence(name);

        int tenantID = Oauth2ScopeUtils.getTenantID();
        OAuthScopeCache.getInstance().clearCacheEntry(new OAuthScopeCacheKey(name, Integer.toString(tenantID)));

        try {
            OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO().deleteScopeByName(name, tenantID);
            if (log.isDebugEnabled()) {
                log.debug("Scope: " + name + " is deleted from the database.");
            }
        } catch (IdentityOAuth2ScopeServerException e) {
            throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_FAILED_TO_DELETE_SCOPE_BY_NAME, name, e);
        }
    }

    /**
     * Update the scope of the given scope ID
     *
     * @param updatedScope details of updated scope
     * @return updated scope
     * @throws IdentityOAuth2ScopeException
     */
    public Scope updateScope(Scope updatedScope) throws IdentityOAuth2ScopeException {

        updateScopePreValidation(updatedScope);
        // Check whether a scope exists with the provided scope name which to be deleted.
        validateScopeExistence(updatedScope.getName());

        int tenantID = Oauth2ScopeUtils.getTenantID();
        try {
            OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO().updateScopeByName(updatedScope, tenantID);
        } catch (IdentityOAuth2ScopeServerException e) {
            throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_FAILED_TO_UPDATE_SCOPE_BY_NAME, updatedScope.getName(), e);
        }

        OAuthScopeCache.getInstance().addToCache(new OAuthScopeCacheKey(updatedScope.getName(),
                Integer.toString(tenantID)), updatedScope);
        OIDCScopeClaimCache.getInstance().clearScopeClaimMap(tenantID);
        return updatedScope;
    }

    /**
     * Get OAuth scope consent given for an application by the user.
     *
     * @param userId    User Id.
     * @param appId     Application Id.
     * @param tenantId  Tenant Id.
     * @return  {@link OAuth2ScopeConsentResponse}.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public OAuth2ScopeConsentResponse getUserConsentForApp(String userId, String appId, int tenantId)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        validateAppId(appId);
        try {
             UserApplicationScopeConsentDO userConsent = OAuthTokenPersistenceFactory.getInstance()
                     .getOAuthUserConsentedScopesDAO()
                    .getUserConsentForApplication(userId, appId, tenantId);
             OAuth2ScopeConsentResponse consentResponse = new OAuth2ScopeConsentResponse(userId, appId, tenantId,
                     userConsent.getApprovedScopes(), userConsent.getDeniedScopes());
             if (log.isDebugEnabled()) {
                 log.debug("Successfully retrieved the user consent for userId : " + userId + " and appId: "
                         + appId);
             }
             return consentResponse;
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_RETRIEVE_USER_CONSENTS_FOR_APP.getMessage(), userId,
                    appId, tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_RETRIEVE_USER_CONSENTS_FOR_APP.getCode(), msg, e);
        }
    }

    /**
     * Get list of scope consents given by user for all the applications.
     *
     * @param userId    User Id.
     * @param tenantId  Tenant Id.
     * @return  List of {@link OAuth2ScopeConsentResponse} objects.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public List<OAuth2ScopeConsentResponse> getUserConsents(String userId, int tenantId)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        try {
            List<UserApplicationScopeConsentDO> userConsents = OAuthTokenPersistenceFactory.getInstance()
                    .getOAuthUserConsentedScopesDAO()
                    .getUserConsents(userId, tenantId);
            if (log.isDebugEnabled()) {
                log.debug("Successfully retrieved the user consents for userId : " + userId);
            }
            return userConsents.stream()
                    .map(consent -> new OAuth2ScopeConsentResponse(userId, consent.getAppId(), tenantId,
                            consent.getApprovedScopes(), consent.getDeniedScopes()))
                    .collect(Collectors.toList());
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_RETRIEVE_USER_CONSENTS.getMessage(), userId, tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_RETRIEVE_USER_CONSENTS.getCode(), msg, e);
        }
    }

    /**
     * Add an OAuth scope consent given for an application by an user.
     *
     * @param userId            User Id.
     * @param appId             Application Id.
     * @param tenantId          Tenant Id.
     * @param approvedScopes    List of approved scopes.
     * @param deniedScopes      List of denied scopes.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public void addUserConsentForApplication(String userId, String appId, int tenantId,
                                             List<String> approvedScopes, List<String> deniedScopes)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        validateAppId(appId);
        try {
            UserApplicationScopeConsentDO userApplicationScopeConsents =
                    new UserApplicationScopeConsentDO(appId, approvedScopes, deniedScopes);
            OAuthTokenPersistenceFactory.getInstance().getOAuthUserConsentedScopesDAO()
                    .addUserConsentForApplication(userId, tenantId, userApplicationScopeConsents);
            if (log.isDebugEnabled()) {
                log.debug("Successfully added the user consent for OAuth scopes for user : " + userId +
                        " and application name : " + appId + " in tenant with id : " + tenantId);
            }
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_ADD_USER_CONSENT_FOR_APP.getMessage(), userId, appId,
                    tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_ADD_USER_CONSENT_FOR_APP.getCode(), msg, e);
        }
    }

    /**
     * Update consent given for OAuth scopes by a user for a given application.
     *
     * @param userId            User Id.
     * @param appId             Application Id.
     * @param tenantId          Tenant Id.
     * @param approvedScopes    List of approved scopes.
     * @param deniedScopes      List of denied scopes.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public void updateUserConsentForApplication(String userId, String appId, int tenantId,
                                                List<String> approvedScopes, List<String> deniedScopes)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        validateAppId(appId);
        try {
            UserApplicationScopeConsentDO updatedUserApplicationScopeConsents =
                    new UserApplicationScopeConsentDO(appId, approvedScopes, deniedScopes);
            OAuthTokenPersistenceFactory.getInstance().getOAuthUserConsentedScopesDAO()
                    .updateExistingConsentForApplication(userId, tenantId, updatedUserApplicationScopeConsents);
            if (log.isDebugEnabled()) {
                log.debug("Successfully updated the user consent for OAuth scopes for user : " + userId +
                        " and application : " + appId + " in tenant with Id : " + tenantId);
            }
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_UPDATE_USER_CONSENT_FOR_APP.getMessage(), userId, appId,
                    tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_UPDATE_USER_CONSENT_FOR_APP.getCode(), msg, e);
        }
    }

    /**
     * Revoke scope consents of a given user for a given application.
     *
     * @param userId    User Id.
     * @param appId     Application Id.
     * @param tenantId  Tenant Id.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public void revokeUserConsentForApplication(String userId, String appId, int tenantId)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        validateAppId(appId);
        try {
            OAuthTokenPersistenceFactory.getInstance().getOAuthUserConsentedScopesDAO()
                    .deleteUserConsentOfApplication(userId, appId, tenantId);
            if (log.isDebugEnabled()) {
                log.debug("Successfully revoked the user consents for OAuth scopes for user : " + userId +
                        " and application : " + appId + " for tenant with Id : " + tenantId);
            }
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_REVOKE_USER_CONSENT_FOR_APP.getMessage(), userId, appId,
                    tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_REVOKE_USER_CONSENT_FOR_APP.getCode(), msg, e);
        }
    }

    /**
     * Revoke all scope consents for the user.
     *
     * @param userId    User Id.
     * @param tenantId  Tenant Id.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public void revokeUserConsents(String userId, int tenantId)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        try {
            OAuthTokenPersistenceFactory.getInstance().getOAuthUserConsentedScopesDAO()
                    .deleteUserConsents(userId, tenantId);
            if (log.isDebugEnabled()) {
                log.debug("Successfully deleted the user consents OAuth scopes for user : " + userId +
                        " in tenant with Id : " + tenantId);
            }
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_REVOKE_USER_CONSENT.getMessage(), userId, tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_REVOKE_USER_CONSENT.getCode(), msg, e);
        }
    }

    /**
     * Check if user has already consented for requested scopes.
     *
     * @param userId                            User Id.
     * @param appId                             Application Id.
     * @param tenantId                          Tenant Id.
     * @param consentRequiredApprovedScopes     List of consent required approved scopes.
     * @param consentRequiredDeniedScopes       List of consent required denied scopes.
     * @return true if user has already provided the consent.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public boolean hasUserAlreadyProvidedConsentForAllRequestedScopes(String userId, String appId,
                                                                      int tenantId,
                                                                      List<String> consentRequiredApprovedScopes,
                                                                      List<String> consentRequiredDeniedScopes)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        validateAppId(appId);
        try {
            UserApplicationScopeConsentDO existingConsents = OAuthTokenPersistenceFactory.getInstance()
                    .getOAuthUserConsentedScopesDAO()
                    .getUserConsentForApplication(userId, appId, tenantId);
            boolean consentRequiredApprovedScopesAreApproved = true;
            boolean consentRequiredDisapprovedScopesAreDisapproved = true;
            if (CollectionUtils.isNotEmpty(consentRequiredApprovedScopes)) {
                consentRequiredApprovedScopesAreApproved = consentRequiredApprovedScopes
                        .stream().allMatch(scope -> existingConsents.getApprovedScopes().contains(scope));
            }
            if (CollectionUtils.isNotEmpty(consentRequiredDeniedScopes)) {
                consentRequiredDisapprovedScopesAreDisapproved = consentRequiredDeniedScopes
                        .stream().allMatch(scope -> existingConsents.getDeniedScopes().contains(scope));
            }
            return consentRequiredApprovedScopesAreApproved && consentRequiredDisapprovedScopesAreDisapproved;
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_CHECK_ALREADY_USER_CONSENTED.getMessage(), userId, appId,
                    tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_CHECK_ALREADY_USER_CONSENTED.getCode(), msg, e);
        }
    }

    /**
     * Check if the user already has an existing consent for the application.
     *
     * @param userId    User id.
     * @param appId     Application id.
     * @param tenantId  Tenant id.
     * @return  True if user already has an existing consent.
     * @throws IdentityOAuth2ScopeConsentException
     */
    public boolean isUserHasAnExistingConsentForApp(String userId, String appId, int tenantId)
            throws IdentityOAuth2ScopeConsentException {

        validateUserId(userId);
        validateAppId(appId);
        try {
            boolean consentExists = false;
            UserApplicationScopeConsentDO existingConsents = OAuthTokenPersistenceFactory.getInstance()
                    .getOAuthUserConsentedScopesDAO()
                    .getUserConsentForApplication(userId, appId, tenantId);
            if (CollectionUtils.isNotEmpty(existingConsents.getApprovedScopes()) ||
                    CollectionUtils.isNotEmpty(existingConsents.getDeniedScopes())) {
                consentExists = true;
            }
            if (log.isDebugEnabled()) {
                log.debug("Existing consent status : " + consentExists + " for user : " + userId +
                        ", app : " + appId + " in tenant with id : " + tenantId);
            }
            return consentExists;
        } catch (IdentityOAuth2ScopeConsentException e) {
            String msg = String.format(ERROR_CODE_FAILED_TO_CHECK_EXISTING_CONSENTS_FOR_USER.getMessage(), userId,
                    appId, tenantId);
            throw new IdentityOAuth2ScopeConsentException(
                    ERROR_CODE_FAILED_TO_CHECK_EXISTING_CONSENTS_FOR_USER.getCode(), msg, e);
        }
    }

    /**
     * Valida user id parameter.
     *
     * @param userId  User Id.
     * @throws IdentityOAuth2ScopeConsentException
     */
    private void validateUserId(String userId) throws IdentityOAuth2ScopeConsentException {

        if (StringUtils.isBlank(userId)) {
            throw new IdentityOAuth2ScopeConsentClientException("User ID can't be null/empty.");
        }
    }

    /**
     * Validate application Id parameter.
     *
     * @param appId   Application Id.
     * @throws IdentityOAuth2ScopeConsentException
     */
    private void validateAppId(String appId) throws IdentityOAuth2ScopeConsentException {

        if (StringUtils.isBlank(appId)) {
            throw new IdentityOAuth2ScopeConsentClientException("Application name can't be null/empty.");
        }
    }

    /**
     * List scopes with filtering
     *
     * @param startIndex Start Index of the result set to enforce pagination
     * @param count      Number of elements in the result set to enforce pagination
     * @param includeOIDCScopes Include OIDC scopes as well.
     * @return List of available scopes
     * @throws IdentityOAuth2ScopeServerException
     */
    private Set<Scope> listScopesWithPagination(Integer startIndex, Integer count, boolean includeOIDCScopes)
            throws IdentityOAuth2ScopeServerException {

        Set<Scope> scopes;

        if (count == null || count < 0) {
            count = Oauth2ScopeConstants.MAX_FILTER_COUNT;
        }

        if (startIndex == null || startIndex < 1) {
            startIndex = 1;
        }

        // Database handles start index as 0
        if (startIndex > 0) {
            startIndex--;
        }

        try {
            scopes = OAuthTokenPersistenceFactory.getInstance().getOAuthScopeDAO()
                    .getScopesWithPagination(startIndex, count, Oauth2ScopeUtils.getTenantID(), includeOIDCScopes);
        } catch (IdentityOAuth2ScopeServerException e) {
            throw Oauth2ScopeUtils.generateServerException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_FAILED_TO_GET_ALL_SCOPES_PAGINATION, e);
        }
        return scopes;
    }

    /**
     * Scope validation before adding the scope.
     *
     * @param scope Scope.
     * @throws IdentityOAuth2ScopeClientException
     */
    private void addScopePreValidation(Scope scope) throws IdentityOAuth2ScopeClientException {

        validateScopeName(scope.getName());
        validateRegex(scope.getName());
        validateDisplayName(scope.getDisplayName());
    }

    /**
     * Do the validation before updating the scope.
     *
     * @param updatedScope Updated scope.
     * @throws IdentityOAuth2ScopeClientException
     */
    private void updateScopePreValidation(Scope updatedScope) throws IdentityOAuth2ScopeClientException {

        validateScopeName(updatedScope.getName());
        validateDisplayName(updatedScope.getDisplayName());
    }

    /**
     * Check whether scope name is provided or not.
     *
     * @param scopeName Scope name.
     * @throws IdentityOAuth2ScopeClientException
     */
    private void validateScopeName(String scopeName) throws IdentityOAuth2ScopeClientException {

        // Check whether the scope name is provided.
        if (StringUtils.isBlank(scopeName)) {
            throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_BAD_REQUEST_SCOPE_NAME_NOT_SPECIFIED, null);
        }
        validateWhiteSpaces(scopeName);
    }

    private void validateRegex(String scopeName) throws IdentityOAuth2ScopeClientException {

        Pattern regexPattern = Pattern.compile(SCOPE_VALIDATION_REGEX);
        if (!regexPattern.matcher(scopeName).matches()) {
            throw Oauth2ScopeUtils.generateClientException
                    (ERROR_CODE_BAD_REQUEST_SCOPE_NAME_NOT_SATIFIED_THE_REGEX, scopeName);
        }
    }

    /**
     * Check whether scope name contains any white spaces.
     *
     * @param scopeName Scope name.
     * @throws IdentityOAuth2ScopeClientException
     */
    private void validateWhiteSpaces(String scopeName) throws IdentityOAuth2ScopeClientException {

        // Check whether the scope name contains any white spaces.
        Pattern pattern = Pattern.compile("\\s");
        Matcher matcher = pattern.matcher(scopeName);
        boolean foundWhiteSpace = matcher.find();

        if (foundWhiteSpace) {
            throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_BAD_REQUEST_SCOPE_NAME_CONTAINS_WHITESPACES, scopeName);
        }
    }

    /**
     * Check whether display name is provided or empty.
     *
     * @param displayName Display name.
     * @throws IdentityOAuth2ScopeClientException
     */
    private void validateDisplayName(String displayName) throws IdentityOAuth2ScopeClientException {

        // Check whether the scope display name is provided.
        if (StringUtils.isBlank(displayName)) {
            throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_BAD_REQUEST_SCOPE_DISPLAY_NAME_NOT_SPECIFIED, null);
        }
    }

    /**
     * Check whether scope exist or not, if scope does not exist trow not found error.
     *
     * @param scopeName Scope name.
     * @throws IdentityOAuth2ScopeException
     */
    private void validateScopeExistence(String scopeName) throws IdentityOAuth2ScopeException {

        // Check whether a scope exists with the provided scope name which to be updated.
        boolean isScopeExists = isScopeExists(scopeName);
        if (!isScopeExists) {
            throw Oauth2ScopeUtils.generateClientException(Oauth2ScopeConstants.ErrorMessages.
                    ERROR_CODE_NOT_FOUND_SCOPE, scopeName);
        }
    }
}
