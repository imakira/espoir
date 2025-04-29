{
  description = "";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  inputs.clj-nix.url = "github:jlesquembre/clj-nix";
  outputs = { self, flake-utils, nixpkgs, clj-nix }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [
            clj-nix.overlays.default
          ];
        };
      in {
        # devShells.default = (fhsFn "/usr/bin/env bash").env;
        packages.default = pkgs.buildNpmPackage rec {
          pname = "espoir";
          nativeBuildInputs = [ pkgs.babashka pkgs.clojure ];
          npmBuildScript = "release";
          version = "0.0.1";
          src = "${self}";
          npmDepsHash = "sha256-JF87TaZLVKS0v+Pc50kECjlhrqqGr2TCFi4+HqCYPeQ=";
          deps-cache = pkgs.mk-deps-cache { lockfile = ./deps-lock.json; };
          # npmPackFlags = [ "--ignore-scripts" ];
          # dontNpmBuild = true;
          # TODO
          # this step only works for dependencies fetched by mvn
          # Make shadow-cljs use cached deps
          preBuild = ''
            bb -e '(-> (slurp "deps.edn")
                       rewrite-clj.zip/of-string
                       (rewrite-clj.zip/assoc :mvn/local-repo "${deps-cache}/.m2/repository")
                       rewrite-clj.zip/root-string
                       (#(spit "deps.edn" %)))'
          '';
        };
      }

    );
}
