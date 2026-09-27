Feature: The bill of materials the package installs describes the package

  An operator, and an update gate, read /usr/share/sokar/sbom/sokar.cdx.json as what the package
  holds. It was once made from the whole build: it named the acceptance kit's cucumber and junit
  and left out picocli and every Sokar module. Checked here on a machine that installed the package.

  Scenario Outline: the bill names what ships
    Then the bill at "/usr/share/sokar/sbom/sokar.cdx.json" names "<component>"

    Examples:
      | component     |
      | sokar-app     |
      | sokard        |
      | sokar-core    |
      | picocli       |

  Scenario Outline: the bill names nothing that only builds or tests it
    Then the bill at "/usr/share/sokar/sbom/sokar.cdx.json" does not name "<component>"

    Examples:
      | component             |
      | cucumber-core         |
      | junit-platform-engine |
      | sshj                  |
      | sokar-acceptance-kit  |
      | sokar-machines        |
      | sokar-release         |
