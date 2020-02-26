package main

import (
	"fmt"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"

	"github.com/kovetskiy/stash"
	"github.com/reconquest/atlassian-external-hooks/integration_tests/internal/external_hooks"
	"github.com/reconquest/atlassian-external-hooks/integration_tests/internal/lojban"
	"github.com/reconquest/atlassian-external-hooks/integration_tests/internal/runner"
	"github.com/reconquest/karma-go"
	"github.com/reconquest/pkg/log"
	"github.com/stretchr/testify/assert"
)

type Suite struct {
	*runner.Runner
	*assert.Assertions
}

type TestParams map[string]interface{}

type Addon struct {
	Version string
	Path    string
}

func NewSuite() *Suite {
	return &Suite{}
}

func (suite *Suite) WithParams(
	params TestParams,
	tests ...func(TestParams),
) runner.Suite {
	return func(run *runner.Runner, assert *assert.Assertions) {
		suite.Runner = run
		suite.Assertions = assert

		for _, test := range tests {
			name := runtime.FuncForPC(reflect.ValueOf(test).Pointer()).Name()
			name = strings.TrimPrefix(name, "main.(*Suite).")
			name = strings.TrimSuffix(name, "-fm")

			log.Infof(
				karma.Describe("params", params),
				"{test} running %s",
				name,
			)

			test(params)
		}
	}
}

func (suite *Suite) ConfigureHook(
	key string,
	context *external_hooks.Context,
	name string,
	script []byte,
) *external_hooks.Hook {
	err := suite.Bitbucket().WriteFile(
		filepath.Join("shared", "external-hooks", name),
		script,
		0777,
	)
	suite.NoError(err, "should be able to write hook script to container")

	var settings = external_hooks.NewSettings().
		UseSafePath(true).
		WithExecutable(name)

	var hook *external_hooks.Hook

	switch key {
	case external_hooks.HOOK_KEY_PRE_RECEIVE:
		hook = context.PreReceive(settings)
	case external_hooks.HOOK_KEY_POST_RECEIVE:
		hook = context.PostReceive(settings)
	case external_hooks.HOOK_KEY_MERGE_CHECK:
		hook = context.MergeCheck(settings)
	}

	addon := suite.ExternalHooks()

	hook.Configure()
	err = addon.Enable(key, context)
	suite.NoError(err, "should be able to enable pre-receive hook")

	return hook
}

func (suite *Suite) ConfigurePreReceiveHook(
	context *external_hooks.Context,
	name string,
	script []byte,
) *external_hooks.Hook {
	return suite.ConfigureHook(
		external_hooks.HOOK_KEY_PRE_RECEIVE,
		context,
		name,
		script,
	)
}

func (suite *Suite) ConfigurePostReceiveHook(
	context *external_hooks.Context,
	name string,
	script []byte,
) *external_hooks.Hook {
	return suite.ConfigureHook(
		external_hooks.HOOK_KEY_POST_RECEIVE,
		context,
		name,
		script,
	)
}

func (suite *Suite) ConfigureMergeCheckHook(
	context *external_hooks.Context,
	name string,
	script []byte,
) *external_hooks.Hook {
	return suite.ConfigureHook(
		external_hooks.HOOK_KEY_MERGE_CHECK,
		context,
		name,
		script,
	)
}

func (suite *Suite) ExternalHooks() *external_hooks.Addon {
	return &external_hooks.Addon{
		BitbucketURI: suite.Bitbucket().GetConnectorURI(),
	}
}

func (suite *Suite) CreateRandomProject() *stash.Project {
	project, err := suite.Bitbucket().Projects().
		Create(lojban.GetRandomID(4))
	suite.NoError(err, "unable to create project")

	return project
}

func (suite *Suite) CreateRandomRepository(
	project *stash.Project,
) *stash.Repository {
	repository, err := suite.Bitbucket().Repositories(project.Key).
		Create(lojban.GetRandomID(4))
	suite.NoError(err, "unable to create repository")

	return repository
}

func (suite *Suite) CreateRandomPullRequest(
	project *stash.Project,
	repository *stash.Repository,
) *stash.PullRequest {
	git := suite.GitClone(repository)

	suite.GitCommitRandomFile(git)

	_, err := git.Push()
	suite.NoError(err, "unable to git push into master")

	branch := suite.GitCreateRandomBranch(git)

	suite.GitCommitRandomFile(git)

	_, err = git.Push("origin", branch)
	suite.NoErrorf(err, "unable to git push into branch %s", branch)

	pullRequest, err := suite.Bitbucket().Repositories(project.Key).
		PullRequests(repository.Slug).
		Create(
			"pr."+lojban.GetRandomID(8),
			lojban.GetRandomID(20),
			branch,
			"master",
		)
	suite.NoError(err, "unable to create pull request")

	return pullRequest
}

func (suite *Suite) CreateSamplePreReceiveHook_FailWithMessage(
	context *external_hooks.Context,
	message string,
) *external_hooks.Hook {
	return suite.ConfigurePreReceiveHook(context, `pre.fail.sh`, text(
		`#!/bin/bash`,
		fmt.Sprintf(`echo %s`, message),
		`exit 1`,
	))
}

func (suite *Suite) CreateSamplePostReceiveHook_FailWithMessage(
	context *external_hooks.Context,
	message string,
) *external_hooks.Hook {
	return suite.ConfigurePostReceiveHook(context, `post.fail.sh`, text(
		`#!/bin/bash`,
		fmt.Sprintf(`echo %s`, message),
		`exit 1`,
	))
}

func (suite *Suite) CreateSampleMergeCheckHook_FailWithMessage(
	context *external_hooks.Context,
	message string,
) *external_hooks.Hook {
	return suite.ConfigureMergeCheckHook(context, `merge.fail.sh`, text(
		`#!/bin/bash`,
		fmt.Sprintf(`echo %s`, message),
		`exit 1`,
	))
}