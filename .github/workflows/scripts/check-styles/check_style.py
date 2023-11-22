import os
import requests
from github import Github

# Initialize with the token
g = Github(os.getenv('GITHUB_TOKEN'))
repo = g.get_repo(os.getenv('GITHUB_REPOSITORY'))
pr_number = int(os.getenv('PR_NUMBER'))  # Convert to int since environment variables are strings
pr = repo.get_pull(pr_number)

# Fetch files changed in the PR
files = pr.get_files()

for file in files:
    # Only proceed if it's a .java file for example
    if file.filename.endswith('.java'):
        diff = file.patch  # The changes in the file

        # Check for full stops at the end of comments
        for line in diff.split('\n'):
            if line.startswith('+') and (line.strip().startswith('//') or line.strip().startswith('*')):
                if not line.strip().endswith('.'):
                    pr.create_review_comment(
                        body="Comments should end with a full stop.",
                        commit_id=file.raw_data['sha'],
                        path=file.filename,
                        position=file.raw_data['line']
                    )

        # Check for extra new lines
        if '\n+\n+' in diff:
            pr.create_review_comment(
                body="Please remove extra new lines.",
                commit_id=file.raw_data['sha'],
                path=file.filename,
                position=file.raw_data['line']
            )
