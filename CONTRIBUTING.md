# Contributing to AWS DB Connector

Thank you for taking the time. Bug reports, ideas and pull requests are all welcome, and none of them has to be big.

- [Reporting a bug](#reporting-a-bug)
- [Reporting a security problem](#reporting-a-security-problem)
- [Suggesting a feature](#suggesting-a-feature)
- [Working on the code](#working-on-the-code)
- [Pull requests](#pull-requests)
- [Releases](#releases)

## Reporting a bug

Open a [bug report](https://github.com/angel-milanov/AWS-DB-Connector/issues/new?template=bug_report.yml). The form asks
for what is listed below; the more of it you can give, the less has to be guessed.

**Before you report**

- Update to the [latest release](https://github.com/angel-milanov/AWS-DB-Connector/releases/latest) and try again.
- Look through the [open and closed issues](https://github.com/angel-milanov/AWS-DB-Connector/issues?q=is%3Aissue):
  a comment on an existing report helps more than a second one.
- Check whether the tunnel works without the application. If
  `aws ssm start-session --document-name AWS-StartPortForwardingSessionToRemoteHost …` fails in the same way, the
  cause is in the AWS setup (permissions, SSM Agent, security groups) and not in AWS DB Connector. The
  [troubleshooting table](README.md#troubleshooting) covers the common cases.

**What a good report contains**

| | Where to find it |
| --- | --- |
| Version of AWS DB Connector | *Settings > Updates* |
| How it was installed | msi / exe installer, zip, dmg, deb, tar.gz, or built from source |
| Operating system and version | For Linux also the desktop environment, as tray icons and notifications depend on it |
| Version of the Session Manager plugin and of the AWS CLI | *Settings > Tools* shows the plugin version; `aws --version` |
| Kind of AWS profile | IAM Identity Center (SSO), a role on top of an SSO profile (`source_profile`), access keys, … |
| What you did, what you expected and what happened instead | Steps that reproduce the problem are the most valuable part |
| The activity log | *Activity* tab: click into the log, select all, copy |

The activity log is kept in memory only and is gone when the application exits, so copy it while the problem is on
screen. Two files help in special cases:

- **A failed update on Windows:** `%LOCALAPPDATA%\AWSDBConnector\updates\install.log`. It is removed the next time the
  application starts, so save it first.
- **Connections that disappeared:** a configuration file that could not be read is kept as `config.broken.json` next to
  `config.json` (the locations are listed in the [README](README.md#configuration)).

**Remove what is private.** The application never logs credentials, tokens or database passwords, but the log and the
configuration do contain AWS profile names, account-specific host names, instance ids, session ids and SSO start URLs.
Replace what you do not want to be public, for example `mydb.xxxxxxxx.eu-central-1.rds.amazonaws.com` or
`i-0123456789abcdef0`. Keep the error messages themselves unchanged.

## Reporting a security problem

Please do not open a public issue for a vulnerability. Report it privately through
[*Security > Report a vulnerability*](https://github.com/angel-milanov/AWS-DB-Connector/security/advisories/new)
instead. You will get an answer there, and the report is published together with the fix.

Of particular interest: anything that makes a tunnel reachable for someone other than the local user, anything that
exposes session tokens or credentials, and anything that lets the updater install a package that was not published by
this repository.

## Suggesting a feature

Open a [feature request](https://github.com/angel-milanov/AWS-DB-Connector/issues/new?template=feature_request.yml) and
describe the situation you are in before the solution you have in mind: what you were trying to do, and what stood in
the way. For anything larger than a small fix, please open an issue before writing code, so that no work is spent on
something that does not fit.

The application is deliberately small. It opens tunnels and keeps them open; it is not a database client and does not
store credentials. Suggestions that keep it that way have the best chances.

## Working on the code

### Setup

You need Git and a JDK to run Gradle. The project is compiled with JDK 21, which Gradle downloads if it is missing.
Any IDE that understands Gradle works; IntelliJ IDEA needs nothing but *Open*.

```sh
git clone https://github.com/angel-milanov/AWS-DB-Connector.git
cd AWS-DB-Connector
./gradlew run        # on Windows: gradlew.bat run
```

| Command | |
| --- | --- |
| `./gradlew run` | Starts the application from source. It uses your real configuration and AWS profiles. |
| `./gradlew build` | Compiles, runs the tests and checks that they cover every line. This is what the build server runs for every push and pull request. |
| `./gradlew uiPreview -Pout=<dir>` | Renders the windows with sample data, light and dark, into PNG files. No display and no AWS account needed. |
| `./gradlew jpackageImage` | Builds the native application into `build/jpackage`. Add `-PjpackageType=msi` (or `exe`, `dmg`, `deb`) for an installer. |

Good to know:

- A run from source has no version, so the update check is switched off. To try the updater, build a package with
  `-PappVersion=<an older version>`.
- `jpackageImage` deletes `build/jpackage` first. Exit a copy of the application that is running from there, or the
  build fails on Windows.
- The Windows installers need the [WiX Toolset 3.x](https://wixtoolset.org/). Packages can only be built on the
  operating system they are for; the build server builds all of them for the `main` branch.

### Where things are

```
src/main/kotlin/com/angelmilanov/awsdbconnector/
  Main.kt      the entry point of the process
  App.kt       startup, single instance, shutdown
  aws/         AWS profiles, SDK clients, discovery of EC2 / RDS resources, SSO sign-in
  tunnel/      Tunnel (local port, proxy, reconnect logic), TunnelManager, SsmBackend (Session Manager)
  ui/          main window, connection dialog, settings, tray menu, theme and icons (Swing, FlatLaf)
  update/      update check, download and verification, installation per platform
  model/       configuration: connections and settings, stored as JSON
  util/        activity log, notifications, platform specifics, the real desktop (tray, browser, clipboard)
src/test/kotlin/…
  *Test.kt                        the tests, one file per part of the application
  TestSupport.kt                  the stand-ins the tests use (see below)
  UiPreview, PackageIcons         build helpers
```

`Tunnel` knows nothing about AWS: it talks to a `TunnelBackend`. `SsmBackend` is the real one, and the tests use a
fake one that connects to a local echo server.

### Tests

Every line of the application is run by a test. `./gradlew build` fails if one is not, and writes a report that shows
which: `build/reports/jacoco/test/html/index.html`.

- **The tests need neither an AWS account nor a network connection**, and they leave the computer they run on alone.
  What the application reaches out to is replaced by a stand-in from `TestSupport.kt`:

  | Stand-in | Replaces |
  | --- | --- |
  | `FakeAws` | AWS: a local HTTP server that answers the requests of the AWS SDK, and the AWS config file |
  | `FakePrograms`, `FakeProcess` | Every program the application starts: Session Manager plugin, AWS CLI, installers, `reg`, `gsettings`, … |
  | `FakeBackend`, `FakeSession`, `EchoServer` | Session Manager sessions and the database at the far end |
  | `FakeTray`, `FakeDesktop` | The tray icon, the browser, the file manager and the clipboard |

  The Gradle test task points the home directory, the configuration and the cache at `build/test-home`, so the tests
  never see your connections or AWS profiles.
- **The user interface is tested through its windows.** The tests create them for real, find fields by their labels
  and click the buttons. They therefore need a display: windows flash up for a moment while the tests run, and a
  server without a display runs them with `xvfb-run ./gradlew build`. Modal dialogs are answered with `answering { … }`.
- **Code that depends on the operating system takes it as a parameter** (`Os`), so that the Windows, macOS and Linux
  variants are all tested wherever the tests run.
- **A few places cannot run in a test** because they need the real desktop or end the process: the entry point in
  `Main.kt` and the three thin wrappers in `util/Desktop.kt`. They are marked with `@ExcludeFromJacocoGeneratedReport`
  and a reason, and are left out of the coverage. Keep such code free of logic, and do not use the annotation to get
  a pull request through: put the logic where a test can reach it.
- A fix for a bug comes with a test that fails without the fix.
- Test names are sentences that say what is guaranteed: `` `stop releases the local port and reports a busy port` ``.
- Never wait with a fixed `sleep` for something to happen; use `await("…") { … }`.
- For a change that is visible, run `uiPreview` as well, look at the result in both themes and attach the pictures
  to the pull request: the tests check what a window does, not how it looks.
- Say in the pull request on which operating systems you have tried the change. The real tray, notifications,
  packaging and the updater behave differently on each of them, and nobody has all three at hand.

### Style

- Kotlin with the [official code style](https://kotlinlang.org/docs/coding-conventions.html) (`kotlin.code.style=official`),
  four spaces. There is no hard limit for the length of a line, but nearly all of them stay below 130 characters.
- Comments say why something is the way it is, not what the code does. Have a look at the existing ones.
- Texts in the user interface are short, in sentence case and say what the user can do next:
  *"Local port 5432 is already in use."*
- Never write credentials, tokens or the contents of the session response to the activity log, to notifications or to
  the configuration file.
- No new dependency without a reason. The packages contain a complete Java runtime already, and every library adds to
  what has to be kept up to date.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make the change. Keep a pull request to one topic: a fix and an unrelated clean-up are two pull requests.
3. Run `./gradlew build`.
4. Open the pull request and describe what it changes and why. Link the issue it belongs to (`Fixes #123`).

A few things that save a round of review:

- Commit messages are one short sentence about what the commit does, as in the existing history:
  *"Adds support for MSI/EXE on windows."*
- Leave the versions of the libraries alone, unless the change needs a newer one. [Renovate](renovate.json) updates
  them once a week, and those updates are tested and released on their own.
- Do not change the version number or add tags; that happens when a release is made.
- Update the README if the change is visible to users.

By contributing you agree that your contribution is licensed under the [BSD 3-Clause License](LICENSE) of the project.

## Releases

For maintainers. A release is a tag:

```sh
git tag v1.2.3 && git push origin v1.2.3
```

The [Build workflow](.github/workflows/build.yml) builds the packages for all platforms and publishes them as a GitHub
release; a tag with a suffix (`v1.3.0-rc.1`) becomes a pre-release. Library updates merged by Renovate are released on
their own as the next patch version by the [Release workflow](.github/workflows/release.yml). Both files describe the
details at the top.
