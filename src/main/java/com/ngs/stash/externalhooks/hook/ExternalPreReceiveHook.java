package com.ngs.stash.externalhooks.hook;

import com.atlassian.bitbucket.auth.AuthenticationContext;
import com.atlassian.bitbucket.cluster.ClusterService;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHook;
import com.atlassian.bitbucket.hook.repository.PreRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryHookRequest;
import com.atlassian.bitbucket.hook.repository.RepositoryHookResult;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.bitbucket.repository.RefChange;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryCloneLinksRequest;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.atlassian.bitbucket.server.StorageService;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.bitbucket.setting.SettingsValidator;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.bitbucket.util.NamedLink;
import com.atlassian.cache.CacheFactory;
import com.atlassian.upm.api.license.PluginLicenseManager;
import com.atlassian.upm.api.license.entity.PluginLicense;
import com.atlassian.upm.api.util.Option;
import com.ngs.stash.externalhooks.hook.helpers.RepositoryPathLocator;
import org.apache.commons.io.FilenameUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

public class ExternalPreReceiveHook
    implements PreRepositoryHook<RepositoryHookRequest>, SettingsValidator
{
    private final PluginLicenseManager pluginLicenseManager;

    private static Logger log = Logger.getLogger(
        ExternalPreReceiveHook.class.getSimpleName()
    );

    private AuthenticationContext authCtx;
    private PermissionService permissions;
    private RepositoryService repoService;
    private ClusterService clusterService;
    private ApplicationPropertiesService properties;
    private StorageService storageProperties;
    private RepositoryPathLocator pathLocator;

    public ExternalPreReceiveHook(
        AuthenticationContext authenticationContext,
        PermissionService permissions,
        RepositoryService repoService,
        ApplicationPropertiesService properties,
        PluginLicenseManager pluginLicenseManager,
        ClusterService clusterService,
        StorageService storageProperties,
        CacheFactory cacheFactory
    ) {
        log.setLevel(INFO);
        this.authCtx = authenticationContext;
        this.permissions = permissions;
        this.repoService = repoService;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.pluginLicenseManager = pluginLicenseManager;
        this.clusterService = clusterService;
        this.pathLocator = new RepositoryPathLocator(cacheFactory);
    }

    ExternalPreReceiveHook(
        AuthenticationContext authenticationContext,
        PermissionService permissions,
        RepositoryService repoService,
        ApplicationPropertiesService properties,
        PluginLicenseManager pluginLicenseManager,
        ClusterService clusterService,
        StorageService storageProperties,
        RepositoryPathLocator pathLocator
    ) {
        log.setLevel(INFO);
        this.authCtx = authenticationContext;
        this.permissions = permissions;
        this.repoService = repoService;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.pluginLicenseManager = pluginLicenseManager;
        this.clusterService = clusterService;
        this.pathLocator = pathLocator;
    }

    @Override
    public RepositoryHookResult preUpdate(PreRepositoryHookContext context, RepositoryHookRequest request) {
        return preUpdateImpl(context, request);
    }

    public RepositoryHookResult preUpdateImpl(RepositoryHookContext context, RepositoryHookRequest request) {
        if (!this.isLicenseValid()) {
            return RepositoryHookResult.rejected(
                "License is not valid.",
                "License for External Hooks Plugin is expired.\n"+
                "Visit \"Manage add-ons\" page in your Bitbucket instance for more info."
            );
        }

        Repository repo = request.getRepository();
        Settings settings = context.getSettings();

        Path repoPath = this.pathLocator.getRepositoryDir(repo, storageProperties);
        if (repoPath == null) {
            RepositoryHookResult.rejected("Error while running the Hook",
                    "External Hooks Plugin was not able to run hook in the the repo. " +
                            "Check Bitbucket logs for more info.");
        }

        // compat with < 3.2.0
        List<String> exe = new LinkedList<String>();

        ProcessBuilder pb = createProcessBuilder(repo, repoPath.toAbsolutePath().toString(), exe, settings, request);

        try {
            return runExternalHooks(
                pb,
                request.getRefChanges(),
                "Push rejected by External Hook"
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RepositoryHookResult.rejected(
                "Internal Error",
                "Internal Error occured during External Hooks execution.\n"+
                "Check Bitbucket logs for more info."
            );
        } catch (IOException e) {
            log.log(
                SEVERE,
                "Error running {0} in {1}: {2}",
                new Object[]{exe, repoPath, e}
            );
            return RepositoryHookResult.rejected(
                "Internal Error",
                "Internal Error occured during External Hooks execution.\n"+
                "Check Bitbucket logs for more info."
            );
        }
    }

    public ProcessBuilder createProcessBuilder(
        Repository repo, String repoPath, List<String> exe, Settings settings, RepositoryHookRequest request
    ) {
        exe.add(
            this.getExecutable(
                settings.getString("exe"),
                settings.getBoolean("safe_path", false)
            ).getPath()
        );

        String params = settings.getString("params");
        if (params != null) {
            params = params.trim();
            if (params.length() != 0) {
                for (String arg : settings.getString("params").split("\r\n")) {
                    if (arg.length() != 0) {
                        exe.add(arg);
                    }
                }
            }
        }

        ApplicationUser currentUser = authCtx.getCurrentUser();
        ProcessBuilder pb = new ProcessBuilder(exe);

        Map<String, String> env = pb.environment();
        env.put("STASH_USER_NAME", currentUser.getName());
        if (currentUser.getEmailAddress() != null) {
            env.put("STASH_USER_EMAIL", currentUser.getEmailAddress());
        } else {
            log.log(SEVERE, "Can't get user email address. getEmailAddress() call returns null");
        }
        env.put("STASH_REPO_NAME", repo.getName());

        boolean isAdmin = permissions.hasRepositoryPermission(
            currentUser, repo, Permission.REPO_ADMIN);
        boolean isWrite = permissions.hasRepositoryPermission(currentUser, repo, Permission.REPO_WRITE);
        boolean isDirectAdmin = permissions.hasDirectRepositoryUserPermission(repo, Permission.REPO_ADMIN);
        boolean isDirectWrite = permissions.hasDirectRepositoryUserPermission(repo, Permission.REPO_WRITE);

        env.put("STASH_IS_ADMIN", String.valueOf(isAdmin));
        env.put("STASH_IS_WRITE", String.valueOf(isWrite));
        env.put("STASH_IS_DIRECT_ADMIN", String.valueOf(isDirectAdmin));
        env.put("STASH_IS_DIRECT_WRITE", String.valueOf(isDirectWrite));
        env.put("STASH_REPO_IS_FORK", String.valueOf(repo.isFork()));

        RepositoryCloneLinksRequest.Builder cloneLinksRequestBuilder =
            new RepositoryCloneLinksRequest.Builder();

        cloneLinksRequestBuilder.repository(repo);

        RepositoryCloneLinksRequest cloneLinksRequest =
            cloneLinksRequestBuilder.build();

        Set<NamedLink> cloneLinks = this.repoService.getCloneLinks(
            cloneLinksRequest
        );

        for (NamedLink link : cloneLinks) {
            env.put(
                "STASH_REPO_CLONE_" + link.getName().toUpperCase(),
                link.getHref()
            );
        }

        env.put(
            "STASH_BASE_URL",
            this.properties.getBaseUrl().toString()
        );

        env.put("STASH_PROJECT_NAME", repo.getProject().getName());
        env.put("STASH_PROJECT_KEY", repo.getProject().getKey());

        pb.directory(new File(repoPath));
        pb.redirectErrorStream(true);

        return pb;
    }

    public RepositoryHookResult runExternalHooks(
        ProcessBuilder pb,
        Collection<RefChange> refChanges,
        String summaryMessage
    ) throws InterruptedException, IOException {
        Process process = pb.start();
        InputStreamReader input = new InputStreamReader(
            process.getInputStream(),
            "UTF-8"
        );

        OutputStream output = process.getOutputStream();

        for (RefChange refChange : refChanges) {
            output.write(
                (
                    refChange.getFromHash() + " " +
                    refChange.getToHash() + " " +
                    refChange.getRef().getId() + "\n"
                ).getBytes("UTF-8")
             );
        }
        output.close();

        boolean trimmed = false;
        int data;
        int count = 0;
        StringBuilder builder = new StringBuilder();
        while ((data = input.read()) >= 0) {
            if (count >= 65000) {
                if (!trimmed) {
                    builder.
                        append("\n");
                    builder.
                        append("Hook response exceeds 65K length limit.\n");
                    builder.
                        append("Further output will be trimmed.\n");
                    trimmed = true;
                }
                continue;
            }

            String charToWrite = Character.toString((char)data);

            count += charToWrite.getBytes("utf-8").length;

            builder.append(charToWrite);
        }

        int result = process.waitFor();

        if (result == 0) {
            return RepositoryHookResult.accepted();
        } else {
            String details = builder.toString();
            if (details.length() == 0) {
                details = "Specified executable provides no additional information,\n"+
                    "contact your BitBitbucket Administrator for help.";
            }

            return RepositoryHookResult.rejected(summaryMessage, details);
        }
    }

    @Override
    public void validate(@Nonnull Settings settings, @Nonnull SettingsValidationErrors errors, @Nonnull Scope scope) {
        if (!this.isLicenseValid()) {
            errors.addFieldError("exe",
                "License for External Hooks is expired.");
            return;
        }

        if (this.clusterService.isAvailable() && !settings.getBoolean("safe_path", false)) {
            errors.addFieldError("exe",
                    "Bitbucket is running in DataCenter mode. You must use \"safe mode\" option.");
            return;
        }

        if (!settings.getBoolean("safe_path", false)) {
            if (!permissions.hasGlobalPermission(
                    authCtx.getCurrentUser(), Permission.SYS_ADMIN)) {
                errors.addFieldError("exe",
                    "You should be a Bitbucket System Administrator to edit this field " +
                    "without \"safe mode\" option.");
                return;
            }
        }

        if (settings.getString("exe", "").isEmpty()) {
            errors.addFieldError("exe",
                "Executable is blank, please specify something");
            return;
        }

        File executable = this.getExecutable(
            settings.getString("exe",""),
            settings.getBoolean("safe_path", false));

        boolean isExecutable = false;
        if (executable != null) {
            try {
                isExecutable = executable.canExecute() && executable.isFile();
            } catch (SecurityException e) {
                log.log(
                    SEVERE,
                    "Security exception on " + executable.getPath(),
                    e
                );
                isExecutable = false;
            }
        } else {
            errors.addFieldError("exe",
                "Specified path for executable can not be resolved.");
            return;
        }

        if (!isExecutable) {
            errors.addFieldError("exe",
                "Specified path is not executable file. Check executable flag.");
            return;
        }

        log.log(INFO, "Setting executable " + executable.getPath());
    }

    public File getExecutable(String path, boolean safeDir) {
        File executable = new File(path);
        if (safeDir) {
            path = FilenameUtils.normalize(path);
            if (path == null) {
                executable = null;
            } else {
                String safeBaseDir =
                    getHomeDir().getAbsolutePath() +
                    "/external-hooks/";
                executable = new File(safeBaseDir, path);
            }
        }

        return executable;
    }

    private File getHomeDir() {
        if (this.clusterService.isAvailable()) {
            return this.storageProperties.getSharedHomeDir().toFile();
        } else{
            return this.storageProperties.getHomeDir().toFile();
        }
    }

    public boolean isLicenseValid() {
        Option<PluginLicense> licenseOption = pluginLicenseManager.getLicense();
        if (!licenseOption.isDefined()) {
            return true;
        }

        PluginLicense pluginLicense = licenseOption.get();
        return pluginLicense.isValid();
    }
}
