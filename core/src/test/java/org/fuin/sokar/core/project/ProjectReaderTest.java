package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProjectReader}.
 */
class ProjectReaderTest {

    private static final String MINIMAL = """
            project:
              name: "uc"
              description: "Ultimate Container"
              security_class: "online"
              upstream: "git@github.com:example/uc.git"

            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test.yml");
    }

    @Test
    void readsAProjectDefinition() {

        final Project project = read(MINIMAL);

        assertThat(project.name()).isEqualTo("uc");
        assertThat(project.description()).isEqualTo("Ultimate Container");
        assertThat(project.securityClass()).isEqualTo(SecurityClass.ONLINE);
        assertThat(project.baseImage()).isEqualTo("ubuntu:24.04");
        assertThat(project.imageName()).isEqualTo("sokar/uc");
        assertThat(project.upstream()).isEqualTo("git@github.com:example/uc.git");
    }

    @Test
    void anOnlineProjectWithoutAnUpstreamIsRefused() {

        // Online puts the agent's own remote at the upstream, so without one the class means
        // nothing. Caught here rather than as a git error inside a container.
        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                  security_class: "online"
                image:
                  base_image: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("is online, so it needs an upstream");
    }

    @Test
    void theOtherClassesDoNotNeedAnUpstream() {

        // A guarded project keeps the gate in the path, and an approved push names the upstream
        // on the command line. Requiring it here would make the common case depend on the rare.
        assertThat(read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """).upstream()).isNull();
    }

    @Test
    void ignoresSectionsItDoesNotUnderstandYet() {

        // A project file written for a later version must not stop an older binary from running.
        final Project project = read(MINIMAL + """
                git:
                  upstream_url: "https://example.com/x.git"

                run:
                  gpus: all
                """);

        assertThat(project.name()).isEqualTo("uc");
        // Accepted, and said: a warning names each, so nobody believes it applied.
        assertThat(ProjectReader.unknownKeys(new java.io.StringReader(MINIMAL + "git:\n  upstream_url: x\n"), "t"))
                .containsExactly("git");
    }

    @Test
    void descriptionIsOptional() {

        final Project project = read("""
                project:
                  name: "uc"
                  security_class: "offline"
                image:
                  base_image: "ubuntu:24.04"
                """);

        assertThat(project.description()).isEmpty();
    }

    @Test
    void rejectsAMissingSection() {

        assertThatThrownBy(() -> read("project:\n  name: uc\n  security_class: offline\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("no 'image' section");
    }

    @Test
    void rejectsAMissingKey() {

        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                image:
                  base_image: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("project.security_class");
    }

    @Test
    void rejectsAnUnknownSecurityClass() {

        assertThatThrownBy(() -> read(MINIMAL.replace("online", "yolo")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("offline, guarded, online");
    }

    @Test
    void rejectsANameThatWillNotSurviveTheLayersBelow() {

        assertThatThrownBy(() -> read(MINIMAL.replace("\"uc\"", "\"Ultimate Container\"")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Invalid project name");
    }

    @Test
    void rejectsDuplicateKeys() {

        // A duplicate silently wins in most YAML readers. In a security policy that is a bug.
        assertThatThrownBy(() -> read(MINIMAL.replace("image:\n  base_image",
                "image:\n  base_image: \"a\"\n  base_image")))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Cannot parse");
    }

    @Test
    void reportsTheOriginOfTheProblem() {

        assertThatThrownBy(() -> ProjectReader.read(new StringReader("[]"), "/etc/sokar/project.yml"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("/etc/sokar/project.yml");
    }

    @Test
    void readsDeclaredLimits() {
        final Project project = ProjectReader.read(new java.io.StringReader("""
                project:
                  name: "demo"
                  security_class: "guarded"
                limits:
                  memory: "2g"
                  cpus: "1.5"
                  pids: 512
                image:
                  base_image: "ubuntu:24.04"
                """), "test");

        assertThat(project.limits().memory()).isEqualTo("2g");
        assertThat(project.limits().cpus()).isEqualTo("1.5");
        assertThat(project.limits().pids()).isEqualTo(512);
    }

    @Test
    void fallsBackToTheDefaultLimits() {

        // The case that matters: a project file written before limits existed must still be
        // limited, not silently unlimited.
        final Project project = ProjectReader.read(new java.io.StringReader("""
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """), "test");

        assertThat(project.limits()).isEqualTo(Limits.defaults());
    }

    @Test
    void readsWhereAptShouldFetchFrom() {

        final Project project = read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                  package_sources:
                    - "http://mirror.corp.example/ubuntu/"
                    - "http://archive.ubuntu.com/ubuntu/"
                """);

        assertThat(project.declaresPackageSources()).isTrue();
        assertThat(project.effectivePackageSources())
                .containsExactly("http://mirror.corp.example/ubuntu/",
                        "http://archive.ubuntu.com/ubuntu/");
    }

    @Test
    void namingNoSourcesIsNotTheSameAsNamingTheDefault() {

        // The distinction is load-bearing: a base image with no apt is refused for a project that
        // asked for sources and left alone for one that asked for nothing.
        final Project project = read(MINIMAL);

        assertThat(project.declaresPackageSources()).isFalse();
        assertThat(project.effectivePackageSources())
                .isEqualTo(Project.DEFAULT_PACKAGE_SOURCES)
                .allMatch(source -> source.startsWith("http://"),
                        "Ubuntu's mirrors serve no TLS; an https default never answers");
    }

    @Test
    void refusesSourcesThatCannotBeFetchedFrom() {

        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                  package_sources: ["ftp://mirror.corp.example/ubuntu/"]
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("is not an http or https URL");

        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                  package_sources: []
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Remove the key to use the default");
    }

    @Test
    void readsWhatTheProjectMayReach() {

        final Project project = read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                egress:
                  sets: [maven, nodejs]
                  domains: ["nexus.corp.example"]
                image:
                  base_image: "ubuntu:24.04"
                """);

        assertThat(project.egress().sets()).containsExactly("maven", "nodejs");
        assertThat(project.egress().domains()).containsExactly("nexus.corp.example");
    }

    @Test
    void aProjectThatDeclaresNothingReachesNothing() {

        // Absence is deny, not the generous default. A project file that says nothing about
        // egress must not acquire reachability because a later release added a set.
        assertThat(read(MINIMAL).egress().isEmpty()).isTrue();
    }

    @Test
    void anOfflineProjectCannotDeclareEgress() {

        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                  security_class: "offline"
                egress:
                  sets: [maven]
                image:
                  base_image: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("offline")
                .hasMessageContaining("no egress");
    }

    @Test
    void refusesADomainThatWouldWriteResolverConfiguration() {

        // Each domain becomes a 'server=' and an 'nftset=' line in the container's dnsmasq
        // configuration. A value carrying a newline is not a bad host name - it is extra
        // configuration, written by whoever can edit this file.
        final String[] hostile = {
                "evil.example\\nserver=/#/8.8.8.8",
                "evil.example/../..",
                "*.example.com",
                "http://evil.example",
                "evil example",
                "UPPER.example.com" };

        for (final String domain : hostile) {
            assertThatThrownBy(() -> read("""
                    project:
                      name: "uc"
                      security_class: "guarded"
                    egress:
                      domains: ["%s"]
                    image:
                      base_image: "ubuntu:24.04"
                    """.formatted(domain)))
                    .as("domain %s", domain)
                    .isInstanceOf(ProjectException.class)
                    .hasMessageContaining("Invalid egress domain");
        }
    }

    @Test
    void refusesASetNameThatIsNotOne() {

        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                egress:
                  sets: ["../../etc/passwd"]
                image:
                  base_image: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("Invalid egress set name");
    }

    @Test
    void refusesAScalarWhereAListWasMeant() {

        // 'sets: maven' is a plausible typo for 'sets: [maven]', and silently accepting it would
        // make the file's meaning depend on YAML rather than on what it says.
        assertThatThrownBy(() -> read("""
                project:
                  name: "uc"
                  security_class: "guarded"
                egress:
                  sets: maven
                image:
                  base_image: "ubuntu:24.04"
                """))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("must be a list");
    }

    @Test
    void letsAProjectOptOutOfTheMemoryCapOnPurpose() {
        final Project project = ProjectReader.read(new java.io.StringReader("""
                project:
                  name: "demo"
                  security_class: "guarded"
                limits:
                  memory: "none"
                image:
                  base_image: "ubuntu:24.04"
                """), "test");

        assertThat(project.limits().memory()).isNull();
        assertThat(project.limits().pids()).isEqualTo(2048);
    }

    @Test
    void readsTheCredentialsEveryTaskOfTheProjectHolds() {
        final Project project = read(MINIMAL + """
                credentials:
                  search: brave-search
                  forge-app: forge
                """);

        assertThat(project.credentials()).containsExactly(java.util.Map.entry("search", "brave-search"),
                java.util.Map.entry("forge-app", "forge"));
        assertThat(read(MINIMAL).credentials()).isEmpty();
    }

    @Test
    void anOfflineProjectDeclaresNoCredentials() {
        // The broker would reach the destination for the task, and an offline project reaches nothing.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> read(MINIMAL
                .replace("security_class: \"online\"", "security_class: \"offline\"")
                .replace("  upstream: \"git@github.com:example/uc.git\"\n", "") + """
                credentials:
                  search: brave-search
                """)).isInstanceOf(ProjectException.class).hasMessageContaining("can declare no credentials");
    }
}
