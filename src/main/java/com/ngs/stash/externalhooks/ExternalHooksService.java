package com.ngs.stash.externalhooks;

import java.io.IOException;
import java.util.Date;

import javax.inject.Inject;

import com.atlassian.bitbucket.auth.AuthenticationContext;
import com.atlassian.bitbucket.cluster.ClusterService;
import com.atlassian.bitbucket.hook.repository.RepositoryHookService;
import com.atlassian.bitbucket.hook.script.HookScriptService;
import com.atlassian.bitbucket.permission.Permission;
import com.atlassian.bitbucket.permission.PermissionService;
import com.atlassian.bitbucket.project.Project;
import com.atlassian.bitbucket.project.ProjectService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.scope.ProjectScope;
import com.atlassian.bitbucket.scope.RepositoryScope;
import com.atlassian.bitbucket.server.StorageService;
import com.atlassian.bitbucket.user.SecurityService;
import com.atlassian.bitbucket.user.UserService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.SchedulerServiceException;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.config.JobId;
import com.atlassian.scheduler.config.JobRunnerKey;
import com.atlassian.scheduler.config.RunMode;
import com.atlassian.scheduler.config.Schedule;
import com.atlassian.upm.api.license.PluginLicenseManager;
import com.ngs.stash.externalhooks.util.Walker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalHooksService implements JobRunner {
  private static Logger log = LoggerFactory.getLogger(ExternalHooksService.class);

  private final JobId jobId = JobId.of("external-hooks-enable-job");

  private SchedulerService schedulerService;
  private SecurityService securityService;

  private Walker walker;
  private HooksFactory hooksFactory;
  private ClusterService clusterService;

  @Inject
  public ExternalHooksService(
      @ComponentImport UserService userService,
      @ComponentImport RepositoryService repositoryService,
      @ComponentImport SchedulerService schedulerService,
      @ComponentImport HookScriptService hookScriptService,
      @ComponentImport RepositoryHookService repositoryHookService,
      @ComponentImport ProjectService projectService,
      @ComponentImport PluginSettingsFactory pluginSettingsFactory,
      @ComponentImport SecurityService securityService,
      @ComponentImport AuthenticationContext authenticationContext,
      @ComponentImport("permissions") PermissionService permissionService,
      @ComponentImport PluginLicenseManager pluginLicenseManager,
      @ComponentImport ClusterService clusterService,
      @ComponentImport StorageService storageService)
      throws IOException {
    this.schedulerService = schedulerService;
    this.securityService = securityService;
    this.clusterService = clusterService;

    this.walker = new Walker(securityService, userService, projectService, repositoryService);

    // Unfortunately, no way to @ComponentImport it because Named() used here.
    // Consider it to replace with lifecycle aware listener.
    this.hooksFactory = new HooksFactory(
        repositoryHookService,
        new HooksCoordinator(
            userService,
            projectService,
            repositoryService,
            repositoryHookService,
            authenticationContext,
            permissionService,
            pluginLicenseManager,
            clusterService,
            storageService,
            hookScriptService,
            pluginSettingsFactory,
            securityService));
  }

  public void start() {
    log.info("Registering Job for creating HookScripts (plugin enabled / bitbucket restarted)");

    JobRunnerKey runner = JobRunnerKey.of("external-hooks-enable");

    this.schedulerService.registerJobRunner(runner, this);

    try {
      // 10 seconds to give the scheduler some space for maneuver when two instances
      // of bitbucket started the same time in DC. Scheduler will pick one job
      // and replace it with latest one if id is the same.
      //
      // more info:
      // https://docs.atlassian.com/atlassian-scheduler-api/1.6.0/atlassian-scheduler-api/apidocs/com/atlassian/scheduler/SchedulerService.html#scheduleJob(com.atlassian.scheduler.config.JobId,%20com.atlassian.scheduler.config.JobConfig)
      long offset = 0;
      if (this.clusterService.getInformation().getNodes().size() > 1) {
        offset = 10000L;
      }

      this.schedulerService.scheduleJob(
          this.jobId,
          JobConfig.forJobRunnerKey(runner)
              .withRunMode(RunMode.RUN_ONCE_PER_CLUSTER)
              .withSchedule(Schedule.runOnce(new Date(System.currentTimeMillis() + offset))));
    } catch (SchedulerServiceException e) {
      log.error("unable to schedule external hooks job");
      e.printStackTrace();
    }
  }

  public JobRunnerResponse runJob(JobRunnerRequest request) {
    log.info("Started job for creating HookScripts");

    // if (!this.isPluginLoaded()) {
    //  log.warn("Plugin is not yet completely loaded, waiting");
    //  return JobRunnerResponse.success();
    // }

    securityService
        .withPermission(Permission.SYS_ADMIN, "External Hook Plugin: creating HookScripts")
        .call(() -> {
          enableHookScripts();
          return null;
        });

    this.schedulerService.unscheduleJob(this.jobId);

    log.info("Finished job for creating HookScripts");

    return JobRunnerResponse.success();
  }

  private void enableHookScripts() {
    walker.walk(new Walker.Callback() {
      @Override
      public void onProject(Project project) {
        hooksFactory.install(new ProjectScope(project));
      }

      @Override
      public void onRepository(Repository repository) {
        hooksFactory.install(new RepositoryScope(repository));
      }
    });

    return;
  }
}
