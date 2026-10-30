package com.appsmith.server.constants;

import com.appsmith.server.constants.ce.UrlCE;

public class Url extends UrlCE {
    // Fork: release approvals (submit, approve, reject), see ApplicationApprovalController.
    public static final String APPROVAL_URL = "/api/v1/approvals";
    // Fork: the projects repo's app JSON (edit copy only), see ProjectsRepoController.
    public static final String PROJECTS_REPO_URL = "/api/v1/projects-repo";
}
