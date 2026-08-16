{
  description = "RhythmNest billing service";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfreePredicate = pkg: nixpkgs.lib.getName pkg == "idea";
      };

      projectJdk = pkgs.jdk21;
      productionLauncher = pkgs.writeShellApplication {
        name = "rhythmnest-billing";
        text = ''
          env_file="$PWD/.env"
          if [[ -f "$env_file" ]]; then
            set -a
            # .env is trusted deployment configuration and may use shell quoting.
            # shellcheck source=/dev/null
            source "$env_file"
            set +a
          fi

          exec "$(dirname "$0")/rhythmnest-billing-server" "$@"
        '';
      };

      server = pkgs.stdenv.mkDerivation (finalAttrs: {
        pname = "rhythmnest-billing";
        version = "1.0.0";
        src = pkgs.lib.fileset.toSource {
          root = ./.;
          fileset = pkgs.lib.fileset.unions [
            ./build.gradle.kts
            ./settings.gradle.kts
            ./libs.versions.toml
            ./src
            ./resources
            ./test
          ];
        };

        nativeBuildInputs = [
          pkgs.gradle
          pkgs.makeWrapper
        ];

        mitmCache = pkgs.gradle.fetchDeps {
          pkg = finalAttrs.finalPackage;
          data = ./deps.json;
        };

        gradleFlags = [ "-Dorg.gradle.java.home=${projectJdk}/lib/openjdk" ];
        gradleBuildTask = "installDist";
        doCheck = true;

        preBuild = ''
          export JAVA_HOME=${projectJdk}/lib/openjdk
        '';

        installPhase = ''
          runHook preInstall
          mkdir -p "$out"
          cp -R build/install/rhythmnest-billing/. "$out"
          mv "$out/bin/rhythmnest-billing" "$out/bin/rhythmnest-billing-server"
          wrapProgram "$out/bin/rhythmnest-billing-server" \
            --set JAVA_HOME ${projectJdk}/lib/openjdk \
            --prefix PATH : ${projectJdk}/bin
          install -m 755 ${productionLauncher}/bin/rhythmnest-billing "$out/bin/rhythmnest-billing"
          runHook postInstall
        '';

        meta = {
          description = "RhythmNest billing server";
          mainProgram = "rhythmnest-billing";
        };
      });

      gradleProject = pkgs.writeShellApplication {
        name = "gradle-project";
        runtimeInputs = [
          projectJdk
          pkgs.gradle
        ];
        text = ''
          export JAVA_HOME=${projectJdk}/lib/openjdk
          export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-''${XDG_CACHE_HOME:-$HOME/.cache}/rhythmnest-billing/gradle}"
          exec gradle --no-daemon "$@"
        '';
      };

      development = pkgs.writeShellApplication {
        name = "rhythmnest-billing-dev";
        runtimeInputs = [
          pkgs.postgresql_16
        ];
        text = ''
          project_root="$PWD"
          if [[ ! -f "$project_root/flake.nix" || ! -f "$project_root/build.gradle.kts" ]]; then
            echo "Run this command from the rhythmnest_billing project root." >&2
            exit 1
          fi

          env_file="$project_root/.env"
          if [[ -f "$env_file" ]]; then
            set -a
            # shellcheck source=/dev/null
            source "$env_file"
            set +a
          fi

          runtime_dir="$(mktemp -d "''${TMPDIR:-/tmp}/rhythmnest-billing.XXXXXX")"
          data_dir="$runtime_dir/postgres"
          db_port="''${HERMIMORPH_BILL_DEV_DB_PORT:-55432}"

          cleanup() {
            if [[ -s "$data_dir/postmaster.pid" ]]; then
              pg_ctl -D "$data_dir" -m fast -w stop >/dev/null
            fi
            rm -rf "$runtime_dir"
          }
          trap cleanup EXIT INT TERM

          initdb -D "$data_dir" --no-locale --encoding=UTF8 --auth=trust --username=postgres >/dev/null
          pg_ctl -D "$data_dir" -o "-h 127.0.0.1 -p $db_port -k $runtime_dir" -w start >/dev/null
          createdb -h 127.0.0.1 -p "$db_port" -U postgres rhythmnest

          export HERMIMORPH_BILL_HOST="''${HERMIMORPH_BILL_HOST:-127.0.0.1}"
          export HERMIMORPH_BILL_PORT="''${HERMIMORPH_BILL_PORT:-8080}"
          export HERMIMORPH_BILL_API_TOKEN="''${HERMIMORPH_BILL_API_TOKEN:-dry-run-token}"
          # The dry-run always uses its isolated database, never the .env database connection.
          export HERMIMORPH_BILL_DB_JDBC_URL="jdbc:postgresql://127.0.0.1:$db_port/rhythmnest"
          export HERMIMORPH_BILL_DB_USER=postgres
          export HERMIMORPH_BILL_DB_PASSWORD=dry-run
          export HERMIMORPH_BILL_INITIAL_ADMIN_ID="''${HERMIMORPH_BILL_INITIAL_ADMIN_ID:-dry-run-admin}"

          echo "Dry-run database: temporary PostgreSQL on 127.0.0.1:$db_port"
          echo "Dry-run API: http://$HERMIMORPH_BILL_HOST:$HERMIMORPH_BILL_PORT (token: $HERMIMORPH_BILL_API_TOKEN)"
          ${server}/bin/rhythmnest-billing-server "$@"
        '';
      };
    in
    {
      apps.${system} = {
        default = {
          type = "app";
          program = "${server}/bin/rhythmnest-billing";
        };
        dev = {
          type = "app";
          program = "${development}/bin/rhythmnest-billing-dev";
        };
      };

      packages.${system} = {
        default = server;
        inherit gradleProject server;
      };

      devShells.${system}.default = pkgs.mkShell {
        packages = [
          projectJdk
          pkgs.gradle
          gradleProject
          pkgs.postgresql_16
          pkgs.jetbrains.idea
        ];
        JAVA_HOME = "${projectJdk}/lib/openjdk";
      };
    };
}
