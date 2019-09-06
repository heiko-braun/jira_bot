package io.syndesis.tools.cmd;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueField;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import io.syndesis.tools.Command;
import io.syndesis.tools.EventType;
import io.syndesis.tools.IssueKey;
import io.syndesis.tools.Util;
import org.json.JSONObject;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class PullRequestClosed implements Command {


    public static final String IN_REVIEW = "In Review";
    public static final String TO_PRODUCTIZATION = "To Productization";

    @Override
    public void execute(String repo, EventType eventType, String payload, GitHub github, JiraRestClient jira, Logger logger) {
        logger.info("Processing {}", eventType);

        Object document = Configuration.defaultConfiguration()
                .jsonProvider()
                .parse(payload);

        // try title first
        String title = JsonPath.read(document, "$.pull_request.title");
        Integer pullRequestId = JsonPath.read(document, "$.pull_request.number");
        String  pullRequestHref = JsonPath.read(document, "$.pull_request.html_url");
        Boolean pullRequestIsMerged = JsonPath.read(document, "$.pull_request.merged");

        if(!pullRequestIsMerged) {
            logger.info("Ignore close request for non-merged PR: {}", pullRequestHref);
            return;
        }

        List<IssueKey> issueKeys = IssueKey.parseIssueKeys(title);

        if(issueKeys.isEmpty()) {
            // look at body
            String body = JsonPath.read(document, "$.pull_request.body");
            issueKeys = IssueKey.parseIssueKeys(body);
        }

        if(issueKeys.isEmpty()) {
            logger.info("No issue keys found");
            return;
        }

        // proceed with issue keys
        for (IssueKey key : issueKeys) {
            logger.info("Found {}", key.getFullyQualifiedIssueKey());

            IssueRestClient issueClient = jira.getIssueClient();
            Issue issue = issueClient
                    .getIssue(key.getFullyQualifiedIssueKey())
                    .claim();

            Iterable<Transition> transitions = issueClient.getTransitions(issue).claim();

            logger.info("Current status: {}", issue.getStatus().getName());
            logger.debug("Possible transitions:");
            for (Transition t : transitions) {
                logger.debug("> {}", t.getName());
            }

            if (issue.getStatus().getName().equals(IN_REVIEW)) {
                for (Transition t : transitions) {

                    if (t.getName().equals(TO_PRODUCTIZATION)) {
                        try {
                            logger.info("Moving issue to `{}`", t.getName());
                            issueClient.transition(issue, new TransitionInput(t.getId())).claim();
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to move issue: "+ e.getMessage());
                        }
                    }
                }
            } else {
                logger.warn("Cannot auto transition {} from {} to {}"
                        , issue.getKey()
                        , issue.getStatus().getName(),
                        "Productisation");

                try {
                    GHPullRequest pullRequest = github.getRepository(repo).getPullRequest(pullRequestId);
                    pullRequest
                            .comment("@"+pullRequest.getUser().getLogin()+" Auto transition failed. Please update the status of this Jira ticket manually: "
                                    + "https://issues.jboss.org/browse/" + issue.getKey());

                } catch (IOException e) {
                    throw new RuntimeException("Failed to leave comment: "+e.getMessage());
                }

            }
        }

    }
}