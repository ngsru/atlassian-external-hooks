package com.ngs.stash.externalhooks.hook;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.bitbucket.comment.AddCommentRequest;
import com.atlassian.bitbucket.comment.CommentService;
import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.repository.*;
import com.atlassian.bitbucket.setting.*;
import com.atlassian.bitbucket.auth.*;
import com.atlassian.bitbucket.permission.*;
import com.atlassian.bitbucket.server.*;
import com.atlassian.bitbucket.util.*;

import static java.util.logging.Level.FINER;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.FINEST;

import java.util.Optional;
import java.util.logging.Logger;

import java.util.Collection;
import java.io.*;
import java.util.Map;
import java.util.List;
import java.util.LinkedList;
import java.util.Set;

import java.util.ArrayList;

import com.atlassian.bitbucket.pull.*;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.ngs.stash.externalhooks.hook.data.PullRequestCheck;
import com.ngs.stash.externalhooks.hook.helpers.*;
// import com.google.common.base.Predicate;
// import static com.google.common.base.Charsets.UTF_8;
// import static com.google.common.base.Joiner.on;
// import static com.google.common.base.Throwables.propagate;
// import static com.google.common.collect.Iterables.filter;
// import static com.google.common.collect.Iterables.transform;
// import static com.google.common.collect.Lists.newArrayList;
// import static com.google.common.collect.Ordering.usingToString;
import static com.ngs.stash.externalhooks.hook.ExternalMergeCheckHook.REPO_PROTOCOL.http;
import static com.ngs.stash.externalhooks.hook.ExternalMergeCheckHook.REPO_PROTOCOL.ssh;

import com.atlassian.upm.api.license.entity.PluginLicense;
import com.atlassian.upm.api.util.Option;

import com.atlassian.upm.api.license.PluginLicenseManager;
import net.java.ao.DBParam;
import net.java.ao.Query;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;

@Named("ExternalMergeCheckHook")
public class ExternalMergeCheckHook
    implements RepositoryMergeCheck, RepositorySettingsValidator
{
    private static Logger log = Logger.getLogger(
        ExternalMergeCheckHook.class.getSimpleName()
    );
    private final PluginLicenseManager pluginLicenseManager;
    private AuthenticationContext authCtx;
    private PermissionService permissions;
    private RepositoryService repoService;
    private ApplicationPropertiesService properties;
    private PullRequestService pullRequestService;
    private CommentService commentService;
    private ActiveObjects activeObjects;

    @Inject
    public ExternalMergeCheckHook(
        AuthenticationContext authenticationContext,
        PermissionService permissions,
        RepositoryService repoService,
        ApplicationPropertiesService properties,
        PluginLicenseManager pluginLicenseManager,
        PullRequestService pullRequestService,
        CommentService commentService,
        @ComponentImport ActiveObjects activeObjects
    )
    {
        this.authCtx = authenticationContext;
        this.permissions = permissions;
        this.repoService = repoService;
        this.properties = properties;
        this.pluginLicenseManager = pluginLicenseManager;
        this.pullRequestService = pullRequestService;
        this.commentService = commentService;
        this.activeObjects = activeObjects;
    }

    private static String cloneUrlFromRepository(
        REPO_PROTOCOL protocol,
        Repository repository,
        RepositoryService repoService
    )
    {
        RepositoryCloneLinksRequest request = new RepositoryCloneLinksRequest.Builder().protocol(protocol.name())
            .repository(repository).build();
        final Set<NamedLink> cloneLinks = repoService.getCloneLinks(request);
        return cloneLinks.iterator().hasNext() ? cloneLinks.iterator().next().getHref() : "";
    }

    private static String getPullRequestUrl(
        ApplicationPropertiesService propertiesService,
        PullRequest pullRequest
    )
    {
        return propertiesService.getBaseUrl() +
            "/projects/" +
            pullRequest.getToRef().getRepository().getProject().getKey()
            +
            "/repos/" +
            pullRequest.getToRef().getRepository().getSlug() +
            "/pull-requests/" +
            pullRequest.getId();
    }

    @Override
    public RepositoryHookResult preUpdate(PreRepositoryHookContext context, PullRequestMergeHookRequest request) {
        PullRequest pr = request.getPullRequest();

        final Optional<PullRequestCheck> lastResult = this.getCheck(pr);
        PullRequestCheck check;

        if (lastResult.isPresent() && lastResult.get().getVersion() == pr.getVersion()) {
            log.log(FINER, "Pull Request was already handled. Returning the last result.");
            if (lastResult.get().getWasAccepted()) {
                return RepositoryHookResult.accepted();
            } else {
                return RepositoryHookResult.rejected(
                    lastResult.get().getLastExceptionSummary(),
                    lastResult.get().getLastExceptionDetail()
                );
            }
        } else {
            if (lastResult.isPresent()) {
                check = lastResult.get();
                check.setVersion(pr.getVersion());
                check.setWasHandled(false);
            } else {
                check = this.activeObjects.create(
                    PullRequestCheck.class,
                    new DBParam("PROJECT_ID", pr.getToRef().getRepository().getProject().getId()),
                    new DBParam("REPOSITORY_ID", pr.getToRef().getRepository().getId()),
                    new DBParam("PULL_REQUEST_ID", pr.getId()),
                    new DBParam("VERSION", pr.getVersion())
                );
            }
        }

        Repository repo = pr.getToRef().getRepository();
        Settings settings = context.getSettings();

        // compat with < 3.2.0
        String repoPath = this.properties.getRepositoryDir(repo).getAbsolutePath();
        List<String> exe = new LinkedList<String>();

        ProcessBuilder pb = createProcessBuilder(repo, repoPath, exe, settings, request);

        List<RefChange> refChanges = new ArrayList<RefChange>();
        refChanges.add(
            new ExternalRefChange(
                pr.getToRef().getId(),
                pr.getToRef().getLatestCommit(),
                pr.getFromRef().getLatestCommit(),
                RefChangeType.UPDATE,
                pr.getToRef()
            )
        );

        Map<String, String> env = pb.environment();

        // Using the same env variables as
        // https://github.com/tomasbjerre/pull-request-notifier-for-bitbucket
        env.put("PULL_REQUEST_FROM_HASH", pr.getFromRef().getLatestCommit());
        env.put("PULL_REQUEST_FROM_ID", pr.getFromRef().getId());
        env.put("PULL_REQUEST_FROM_BRANCH", pr.getFromRef().getDisplayId());
        env.put("PULL_REQUEST_FROM_REPO_ID", pr.getFromRef().getRepository().getId() + "");
        env.put("PULL_REQUEST_FROM_REPO_NAME", pr.getFromRef().getRepository().getName() + "");
        env.put("PULL_REQUEST_FROM_REPO_PROJECT_ID", pr.getFromRef().getRepository().getProject().getId() + "");
        env.put("PULL_REQUEST_FROM_REPO_PROJECT_KEY", pr.getFromRef().getRepository().getProject().getKey());
        env.put("PULL_REQUEST_FROM_REPO_SLUG", pr.getFromRef().getRepository().getSlug() + "");
        env.put("PULL_REQUEST_FROM_SSH_CLONE_URL",
            cloneUrlFromRepository(ssh, pr.getFromRef().getRepository(), repoService));
        env.put("PULL_REQUEST_FROM_HTTP_CLONE_URL",
            cloneUrlFromRepository(http, pr.getFromRef().getRepository(), repoService));
        // env.put("PULL_REQUEST_ACTION", prnfbPullRequestAction.getName());
        env.put("PULL_REQUEST_URL", getPullRequestUrl(properties, pr));
        env.put("PULL_REQUEST_ID", pr.getId() + "");
        env.put("PULL_REQUEST_VERSION", pr.getVersion() + "");
        env.put("PULL_REQUEST_AUTHOR_ID", pr.getAuthor().getUser().getId() + "");
        env.put("PULL_REQUEST_AUTHOR_DISPLAY_NAME", pr.getAuthor().getUser().getDisplayName());
        env.put("PULL_REQUEST_AUTHOR_NAME", pr.getAuthor().getUser().getName());
        env.put("PULL_REQUEST_AUTHOR_EMAIL", pr.getAuthor().getUser().getEmailAddress());
        env.put("PULL_REQUEST_AUTHOR_SLUG", pr.getAuthor().getUser().getSlug());
        env.put("PULL_REQUEST_TO_HASH", pr.getToRef().getLatestCommit());
        env.put("PULL_REQUEST_TO_ID", pr.getToRef().getId());
        env.put("PULL_REQUEST_TO_BRANCH", pr.getToRef().getDisplayId());
        env.put("PULL_REQUEST_TO_REPO_ID", pr.getToRef().getRepository().getId() + "");
        env.put("PULL_REQUEST_TO_REPO_NAME", pr.getToRef().getRepository().getName() + "");
        env.put("PULL_REQUEST_TO_REPO_PROJECT_ID", pr.getToRef().getRepository().getProject().getId() + "");
        env.put("PULL_REQUEST_TO_REPO_PROJECT_KEY", pr.getToRef().getRepository().getProject().getKey());
        env.put("PULL_REQUEST_TO_REPO_SLUG", pr.getToRef().getRepository().getSlug() + "");
        env.put("PULL_REQUEST_TO_SSH_CLONE_URL",
            cloneUrlFromRepository(ssh, pr.getToRef().getRepository(), repoService));
        env.put("PULL_REQUEST_TO_HTTP_CLONE_URL",
            cloneUrlFromRepository(http, pr.getToRef().getRepository(), repoService));
        // env.put("PULL_REQUEST_COMMENT_TEXT", getOrEmpty(variables, PULL_REQUEST_COMMENT_TEXT));
        // env.put("PULL_REQUEST_MERGE_COMMIT", getOrEmpty(variables, PULL_REQUEST_MERGE_COMMIT));
        // env.put("PULL_REQUEST_USER_DISPLAY_NAME", applicationUser.getDisplayName());
        // env.put("PULL_REQUEST_USER_EMAIL_ADDRESS", applicationUser.getEmailAddress());
        // env.put("PULL_REQUEST_USER_ID", applicationUser.getId() + "");
        // env.put("PULL_REQUEST_USER_NAME", applicationUser.getName());
        // env.put("PULL_REQUEST_USER_SLUG", applicationUser.getSlug());
        env.put("PULL_REQUEST_TITLE", pr.getTitle());
        // env.put("PULL_REQUEST_REVIEWERS", iterableToString(transform(pr.getReviewers(), (p) -> p.getUser().getDisplayName())));
        // env.put("PULL_REQUEST_REVIEWERS_ID", iterableToString(transform(pr.getReviewers(), (p) -> Integer.toString(p.getUser().getId()))));
        // env.put("PULL_REQUEST_REVIEWERS_SLUG", iterableToString(transform(pr.getReviewers(), (p) -> p.getUser().getSlug())));
        // env.put("PULL_REQUEST_REVIEWERS_APPROVED_COUNT", Integer.toString(newArrayList(filter(pr.getReviewers(), isApproved)).size()));
        // env.put("PULL_REQUEST_PARTICIPANTS_APPROVED_COUNT", Integer.toString(newArrayList(filter(pr.getParticipants(), isApproved)).size()));

        String summaryMsg = "Merge request failed";

        if (!this.isLicenseValid()) {
            return RepositoryHookResult.rejected(
                "License is not valid.",
                "License for External Hooks Plugin is expired.\n" +
                    "Visit \"Manage add-ons\" page in your Bitbucket instance for more info."
            );
        }

        try {
            final RepositoryHookResult result = runExternalHooks(pb, refChanges, summaryMsg);
            check.setWasAccepted(result.isAccepted());
            if (result.isRejected()) {
                check.setLastExceptionSummary(result.getVetoes().get(0).getSummaryMessage());
                check.setLastExceptionDetail(result.getVetoes().get(0).getDetailedMessage());
            }
            check.save();
            if (BooleanUtils.isTrue(settings.getBoolean("add_comments"))) {
                result.getVetoes().removeAll(result.getVetoes());
                result.getVetoes()
                    .add(
                        new SimpleRepositoryHookVeto(
                            "Merge check failed",
                            "See Pull-Request comments for more info"
                        )
                    );
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String detailedMsg = "Interrupted";
            check.setWasAccepted(false);
            check.setLastExceptionDetail(detailedMsg);
            check.setLastExceptionSummary(summaryMsg);
            check.save();
            return RepositoryHookResult.rejected(summaryMsg, detailedMsg);
        } catch (IOException e) {
            log.log(SEVERE, "Error running " + exe + " in " + repoPath, e);
            String detailedMsg = "I/O Error";
            check.setWasAccepted(false);
            check.setLastExceptionDetail(detailedMsg);
            check.setLastExceptionSummary(summaryMsg);
            check.save();
            return RepositoryHookResult.rejected(summaryMsg, detailedMsg);
        }
    }

    @Override
    public void onEnd(@Nonnull PreRepositoryHookContext context,
                      @Nonnull PullRequestMergeHookRequest request,
                      @Nonnull RepositoryHookResult result)
    {
        final PullRequest pr = request.getPullRequest();
        final Settings settings = context.getSettings();

        final Optional<PullRequestCheck> lastCheck = getCheck(pr);

        if (!lastCheck.isPresent()) {
            // this method was called without preUpdate first. This is unexpected.
            return;
        }

        if (lastCheck.get().getVersion() == pr.getVersion() && lastCheck.get().getWasHandled()) {
            return;
        }

        log.log(FINEST, "Adding new comment");

        final StringBuffer comment = new StringBuffer();

        if (result.isRejected()) {

            if (BooleanUtils.isTrue(settings.getBoolean("add_comments"))) {

                result.getVetoes().forEach(repositoryHookVeto -> {
                        if (StringUtils.isEmpty(repositoryHookVeto.getDetailedMessage())) {
                            comment.append(repositoryHookVeto.getSummaryMessage());
                        } else {
                            comment.append(
                                String.format(
                                    "%s\n%s",
                                    repositoryHookVeto.getSummaryMessage(),
                                    repositoryHookVeto.getDetailedMessage()
                                )
                            );
                        }
                    }
                );

                this.commentService.addComment(
                    new AddCommentRequest.Builder(pr, comment.toString()).build()
                );
            }

            if (BooleanUtils.isTrue(settings.getBoolean("decline_pull_request_on_rejection"))) {
                log.log(FINER, "Declining Pull Request as configured");
                this.pullRequestService.decline(
                    new PullRequestDeclineRequest.Builder(
                        pr,
                        pr.getVersion()
                    ).build()
                );
            }
        } else {
            if (BooleanUtils.isTrue(settings.getBoolean("add_comments"))) {
                this.commentService.addComment(
                    new AddCommentRequest.Builder(
                        pr,
                        comment.append("External Hooks: Checks succesful").toString()
                    ).build()
                );
            }
        }

        lastCheck.get().setWasHandled(true);
        lastCheck.get().save();
    }

    private Optional<PullRequestCheck> getCheck(PullRequest pr) {

        log.log(FINEST, "Fetching existing checks for this pull request");

        final PullRequestCheck[] checks = this.activeObjects.find(
            PullRequestCheck.class,
            Query.select().where(
                "PROJECT_ID = ? AND REPOSITORY_ID = ? AND PULL_REQUEST_ID = ?",
                pr.getToRef().getRepository().getProject().getId(),
                pr.getToRef().getRepository().getId(),
                pr.getId()
            )
        );

        if (checks.length > 1) {
            log.log(SEVERE, "More than one checks exist. This is invalid. Returning.");
            return Optional.empty();
        } else if (checks.length == 1) {
            return Optional.of(checks[0]);
        }

        return Optional.empty();
    }

    public ProcessBuilder createProcessBuilder(
        Repository repo, String repoPath, List<String> exe, Settings settings, RepositoryHookRequest request
    )
    {
        ExternalPreReceiveHook impl = new ExternalPreReceiveHook(this.authCtx,
            this.permissions, this.repoService, this.properties, this.pluginLicenseManager);
        return impl.createProcessBuilder(repo, repoPath, exe, settings, request);
    }

    public RepositoryHookResult runExternalHooks(
        ProcessBuilder pb,
        Collection<RefChange> refChanges,
        String summaryMessage
    ) throws InterruptedException, IOException
    {
        ExternalPreReceiveHook impl = new ExternalPreReceiveHook(this.authCtx,
            this.permissions, this.repoService, this.properties, this.pluginLicenseManager);
        return impl.runExternalHooks(pb, refChanges, summaryMessage);
    }

    // private static final Predicate<PullRequestParticipant> isApproved = new Predicate<PullRequestParticipant>() {
    //         @Override
    //         public boolean apply(PullRequestParticipant input) {
    //             return input.isApproved();
    //         }
    //     };

    // private static String iterableToString(Iterable<String> slist) {
    //     List<String> sorted = usingToString().sortedCopy(slist);
    //     return on(',').join(sorted);
    // }

    @Override
    public void validate(
        Settings settings,
        SettingsValidationErrors errors,
        Repository repository
    )
    {
        ExternalPreReceiveHook impl = new ExternalPreReceiveHook(this.authCtx,
            this.permissions, this.repoService, this.properties, this.pluginLicenseManager);
        impl.validate(settings, errors, repository);
    }

    public boolean isLicenseValid() {
        Option<PluginLicense> licenseOption = pluginLicenseManager.getLicense();
        if (!licenseOption.isDefined()) {
            return true;
        }

        PluginLicense pluginLicense = licenseOption.get();
        return pluginLicense.isValid();
    }

    public enum REPO_PROTOCOL {
        ssh, http
    }
}
