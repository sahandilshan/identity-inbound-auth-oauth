/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.openidconnect;

import com.nimbusds.jwt.SignedJWT;
import org.junit.Assert;
import org.mockito.Mockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.testng.PowerMockTestCase;
import org.powermock.reflect.internal.WhiteboxImpl;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.common.model.IdentityProvider;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.application.mgt.internal.ApplicationManagementServiceComponent;
import org.wso2.carbon.identity.application.mgt.internal.ApplicationManagementServiceComponentHolder;
import org.wso2.carbon.identity.common.testng.WithAxisConfiguration;
import org.wso2.carbon.identity.common.testng.WithCarbonHome;
import org.wso2.carbon.identity.common.testng.WithH2Database;
import org.wso2.carbon.identity.common.testng.WithKeyStore;
import org.wso2.carbon.identity.common.testng.WithRealmService;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.TestConstants;
import org.wso2.carbon.identity.oauth2.authz.OAuthAuthzReqMessageContext;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AccessTokenReqDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AccessTokenRespDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AuthorizeReqDTO;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AuthorizeRespDTO;
import org.wso2.carbon.identity.oauth2.internal.OAuth2ServiceComponentHolder;
import org.wso2.carbon.identity.oauth2.keyidprovider.DefaultKeyIDProviderImpl;
import org.wso2.carbon.identity.oauth2.test.utils.CommonTestUtils;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.identity.openidconnect.internal.OpenIDConnectServiceComponentHolder;
import org.wso2.carbon.identity.openidconnect.model.RequestedClaim;
import org.wso2.carbon.identity.testutil.ReadCertStoreSampleUtil;
import org.wso2.carbon.idp.mgt.IdentityProviderManager;
import org.wso2.carbon.idp.mgt.internal.IdpMgtServiceComponentHolder;
import org.wso2.carbon.user.core.service.RealmService;

import java.security.Key;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.wso2.carbon.identity.oauth2.test.utils.CommonTestUtils.setFinalStatic;
import static org.wso2.carbon.utils.multitenancy.MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
import static org.wso2.carbon.utils.multitenancy.MultitenantConstants.SUPER_TENANT_ID;

@WithCarbonHome
@WithAxisConfiguration
@WithH2Database(files = { "dbScripts/h2_with_application_and_token.sql", "dbScripts/identity.sql" })
@WithRealmService(injectToSingletons = {ApplicationManagementServiceComponentHolder.class})
@WithKeyStore
public class DefaultIDTokenBuilderTest extends PowerMockTestCase {

    public static final String TEST_APPLICATION_NAME = "DefaultIDTokenBuilderTest";
    private static final String AUTHORIZATION_CODE = "AuthorizationCode";
    private static final String AUTHORIZATION_CODE_VALUE = "55fe926f-3b43-3681-aecc-dc3ed7938325";
    private DefaultIDTokenBuilder defaultIDTokenBuilder;
    private OAuth2AccessTokenReqDTO tokenReqDTO;
    private OAuthTokenReqMessageContext messageContext;
    private OAuth2AccessTokenRespDTO tokenRespDTO;
    AuthenticatedUser user;

    @BeforeClass
    public void setUp() throws Exception {
        tokenReqDTO = new OAuth2AccessTokenReqDTO();
        messageContext = new OAuthTokenReqMessageContext(tokenReqDTO);
        tokenRespDTO = new OAuth2AccessTokenRespDTO();
        tokenReqDTO.setTenantDomain(SUPER_TENANT_DOMAIN_NAME);
        tokenReqDTO.setClientId(TestConstants.CLIENT_ID);
        tokenReqDTO.setCallbackURI(TestConstants.CALLBACK);

        user = new AuthenticatedUser();
        user.setAuthenticatedSubjectIdentifier(TestConstants.USER_NAME);
        user.setUserName(TestConstants.USER_NAME);
        user.setUserStoreDomain(TestConstants.USER_STORE_DOMAIN);
        user.setTenantDomain(SUPER_TENANT_DOMAIN_NAME);
        user.setFederatedUser(false);

        messageContext.setAuthorizedUser(user);

        messageContext.setScope(TestConstants.OPENID_SCOPE_STRING.split(" "));

        tokenRespDTO.setAccessToken(TestConstants.ACCESS_TOKEN);

        IdentityProvider idp = new IdentityProvider();
        idp.setIdentityProviderName("LOCAL");
        idp.setEnable(true);

        Map<String, Object> configuration = new HashMap<>();
        configuration.put("SSOService.EntityId", "LOCAL");
        configuration.put("SSOService.SAMLECPEndpoint", "https://localhost:9443/samlecp");
        configuration.put("SSOService.ArtifactResolutionEndpoint", "https://localhost:9443/samlartresolve");
        configuration.put("OAuth.OpenIDConnect.IDTokenIssuerID", "https://localhost:9443/oauth2/token");
        WhiteboxImpl.setInternalState(IdentityUtil.class, "configuration", configuration);
        IdentityProviderManager.getInstance().addResidentIdP(idp, SUPER_TENANT_DOMAIN_NAME);
        defaultIDTokenBuilder =  new DefaultIDTokenBuilder();

        ApplicationManagementService applicationMgtService = mock(ApplicationManagementService.class);
        OAuth2ServiceComponentHolder.setApplicationMgtService(applicationMgtService);
        Map<String, ServiceProvider> fileBasedSPs = CommonTestUtils.getFileBasedSPs();
        setFinalStatic(ApplicationManagementServiceComponent.class.getDeclaredField("fileBasedSPs"),
                                       fileBasedSPs);
        when(applicationMgtService
                     .getApplicationExcludingFileBasedSPs(TEST_APPLICATION_NAME, SUPER_TENANT_DOMAIN_NAME))
                .thenReturn(fileBasedSPs.get(TEST_APPLICATION_NAME));
        when(applicationMgtService
                .getServiceProviderNameByClientId(anyString(), anyString(),
                        anyString()))
                .thenReturn(TEST_APPLICATION_NAME);
        RealmService realmService = IdentityTenantUtil.getRealmService();
        HashMap<String, String> claims = new HashMap<>();
        claims.put("http://wso2.org/claims/username", TestConstants.USER_NAME);
        realmService.getTenantUserRealm(SUPER_TENANT_ID).getUserStoreManager()
                    .addUser(TestConstants.USER_NAME, TestConstants.PASSWORD, new String[0], claims,
                             TestConstants.DEFAULT_PROFILE);

        Map<Integer, Certificate> publicCerts = new ConcurrentHashMap<>();
        publicCerts.put(SUPER_TENANT_ID, ReadCertStoreSampleUtil.createKeyStore(getClass())
                                                                .getCertificate("wso2carbon"));
        setFinalStatic(OAuth2Util.class.getDeclaredField("publicCerts"), publicCerts);
        Map<Integer, Key> privateKeys = new ConcurrentHashMap<>();
        privateKeys.put(SUPER_TENANT_ID, ReadCertStoreSampleUtil.createKeyStore(getClass())
                                                                .getKey("wso2carbon", "wso2carbon".toCharArray()));
        setFinalStatic(OAuth2Util.class.getDeclaredField("privateKeys"), privateKeys);

        OpenIDConnectServiceComponentHolder.getInstance()
                .getOpenIDConnectClaimFilters().add(new OpenIDConnectClaimFilterImpl());

        RequestObjectService requestObjectService = Mockito.mock(RequestObjectService.class);
        List<RequestedClaim> requestedClaims =  Collections.EMPTY_LIST;
        when(requestObjectService.getRequestedClaimsForIDToken(anyString())).
                thenReturn(requestedClaims);
        when(requestObjectService.getRequestedClaimsForUserInfo(anyString())).
                thenReturn(requestedClaims);
        OpenIDConnectServiceComponentHolder.getInstance()
                .getOpenIDConnectClaimFilters()
                .add(new OpenIDConnectClaimFilterImpl());
        OpenIDConnectServiceComponentHolder.setRequestObjectService(requestObjectService);
        OAuth2ServiceComponentHolder.setKeyIDProvider(new DefaultKeyIDProviderImpl());

    }

    @DataProvider(name = "TestBuildRequestIDToken")
    public Object[][] buildRequestIDToken() throws Exception {
        // second set of values
        OAuth2AccessTokenReqDTO tokenReqDTO2 = new OAuth2AccessTokenReqDTO();
        tokenReqDTO2.setTenantDomain(SUPER_TENANT_DOMAIN_NAME);
        tokenReqDTO2.setClientId("dabfba9390aa423f8b04332794d83614");
        tokenReqDTO2.setCallbackURI(TestConstants.CALLBACK);
        OAuthTokenReqMessageContext messageContext2 = new OAuthTokenReqMessageContext(tokenReqDTO2);
        messageContext2.setAuthorizedUser(user);
        messageContext2.setScope(TestConstants.OPENID_SCOPE_STRING.split(" "));
        messageContext2.addProperty(AUTHORIZATION_CODE, AUTHORIZATION_CODE_VALUE);

        OAuth2AccessTokenRespDTO tokenRespDTO2 = new OAuth2AccessTokenRespDTO();
        tokenRespDTO2.setAccessToken("2sa9a678f890877856y66e75f605d456");
        AuthenticatedUser user2 = new AuthenticatedUser();
        user2.setAuthenticatedSubjectIdentifier("user2");
        user2.setUserName("user2");
        user2.setUserStoreDomain(TestConstants.USER_STORE_DOMAIN);
        user2.setTenantDomain(SUPER_TENANT_DOMAIN_NAME);
        user2.setFederatedUser(true);
        messageContext2.setAuthorizedUser(user2);

        return new Object[][]{
                {messageContext, tokenRespDTO, TestConstants.CLIENT_ID},
                {messageContext2, tokenRespDTO2, "dabfba9390aa423f8b04332794d83614"}
        };
    }

    @Test(dataProvider = "TestBuildRequestIDToken")
    public void testBuildIDToken(OAuthTokenReqMessageContext messageContext,
                                 OAuth2AccessTokenRespDTO tokenRespDTO, String clientId) throws Exception {

        RealmService realmService = IdentityTenantUtil.getRealmService();
        PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .setUserRealm(realmService.getTenantUserRealm(SUPER_TENANT_ID));
        IdpMgtServiceComponentHolder.getInstance().setRealmService(IdentityTenantUtil.getRealmService());
        String idToken = defaultIDTokenBuilder.buildIDToken(messageContext, tokenRespDTO);
        Assert.assertEquals(SignedJWT.parse(idToken).getJWTClaimsSet().getAudience().get(0),
                clientId);
        Assert.assertEquals(SignedJWT.parse(idToken).getJWTClaimsSet().getIssuer(),
                "https://localhost:9443/oauth2/token");
        }

    @DataProvider(name = "TestBuildAuthzIDToken")
    public Object[][] buildAuthzIDToken() throws Exception {
        OAuth2AuthorizeReqDTO authzTokenReqDTO = new OAuth2AuthorizeReqDTO();
        OAuthAuthzReqMessageContext authzMessageContext = new OAuthAuthzReqMessageContext(authzTokenReqDTO);
        OAuth2AuthorizeRespDTO authzTokenRespDTO = new OAuth2AuthorizeRespDTO();
        authzTokenReqDTO.setTenantDomain(SUPER_TENANT_DOMAIN_NAME);
        authzTokenReqDTO.setConsumerKey(TestConstants.CLIENT_ID);
        authzTokenReqDTO.setIdpSessionIdentifier(TestConstants.IDP_ENTITY_ID_ALIAS);
        authzTokenReqDTO.setUser(user);
        authzTokenRespDTO.setAccessToken(TestConstants.ACCESS_TOKEN);

        OAuth2AuthorizeReqDTO authzTokenReqDTO2 = new OAuth2AuthorizeReqDTO();
        OAuthAuthzReqMessageContext authzMessageContext2 = new OAuthAuthzReqMessageContext(authzTokenReqDTO2);
        OAuth2AuthorizeRespDTO authzTokenRespDTO2 = new OAuth2AuthorizeRespDTO();
        authzTokenReqDTO2.setTenantDomain(SUPER_TENANT_DOMAIN_NAME);
        authzTokenReqDTO2.setConsumerKey("dabfba9390aa423f8b04332794d83614");
        authzTokenReqDTO2.setIdpSessionIdentifier(TestConstants.IDP_ENTITY_ID_ALIAS);
        AuthenticatedUser user2 = new AuthenticatedUser();
        authzTokenReqDTO2.setUser(user2);
        user2.setAuthenticatedSubjectIdentifier("user2");
        user2.setUserName("user2");
        user2.setUserStoreDomain(TestConstants.USER_STORE_DOMAIN);
        user2.setTenantDomain(TestConstants.TENANT_DOMAIN);
        user2.setFederatedUser(true);
        authzTokenRespDTO2.setAccessToken("2sa9a678f890877856y66e75f605d456");

        return new Object[][] {
                {authzMessageContext, authzTokenRespDTO, TestConstants.CLIENT_ID},
                {authzMessageContext2, authzTokenRespDTO2, "dabfba9390aa423f8b04332794d83614"}
        };
    }

    @Test(dataProvider = "TestBuildAuthzIDToken")
    public void testAuthoBuildIDToken(OAuthAuthzReqMessageContext authzMessageContext,
                                      OAuth2AuthorizeRespDTO authzTokenRespDTO,
                                      String clientId) throws Exception {

        RealmService realmService = IdentityTenantUtil.getRealmService();
        PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .setUserRealm(realmService.getTenantUserRealm(SUPER_TENANT_ID));
        IdpMgtServiceComponentHolder.getInstance().setRealmService(IdentityTenantUtil.getRealmService());
        String authoIDToken = defaultIDTokenBuilder.buildIDToken(authzMessageContext, authzTokenRespDTO);
        Assert.assertEquals(SignedJWT.parse(authoIDToken).getJWTClaimsSet().getAudience().get(0),
                clientId);
        Assert.assertEquals(SignedJWT.parse(authoIDToken).getJWTClaimsSet().getIssuer(),
                "https://localhost:9443/oauth2/token");
    }

    @Test
    public void testClientIDNotFoundException() throws Exception {

        RealmService realmService = IdentityTenantUtil.getRealmService();
        PrivilegedCarbonContext.getThreadLocalCarbonContext()
                .setUserRealm(realmService.getTenantUserRealm(SUPER_TENANT_ID));
        IdpMgtServiceComponentHolder.getInstance().setRealmService(IdentityTenantUtil.getRealmService());
        tokenReqDTO.setClientId(null);
        try {
            String authoIDToken = defaultIDTokenBuilder.buildIDToken(messageContext, tokenRespDTO);
            Assert.assertEquals(SignedJWT.parse(authoIDToken).getJWTClaimsSet().getAudience().get(0),
                    TestConstants.CLIENT_ID);
            Assert.assertEquals(SignedJWT.parse(authoIDToken).getJWTClaimsSet().getIssuer(),
                    "https://localhost:9443/oauth2/token");
        } catch (IdentityOAuth2Exception e) {
            Assert.assertEquals(e.getMessage(),
                    "Error occurred while getting app information for client_id: null");
        }
    }
}
