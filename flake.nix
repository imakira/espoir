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

        deps-hash = "sha256-e+Tltq/MnrFTgUUCLQYc01Ka1zqeX2AvZtu7XTNiK4Y=";

        npm-deps = pkgs.buildNpmPackage(finalAttrs: {
          src = self;
          pname = "espoir-npm-deps";
          version = "0.0";
          dontNpmBuild = true;
          npmDepsHash = deps-hash;
          installPhase = ''
          mkdir $out
          cp -r ./node_modules $out/node_modules
          '';
        });

        build = pkgs.mkCljLib {
          projectSrc = self;
          name = "espoir-shadow-build";
          buildCommand = "
          cp -r ${npm-deps}/node_modules ./node_modules
          ${pkgs.nodejs}/bin/npm run release
          ";
          installPhase = ''
          mkdir -p $out
          cp -R * $out/
          '';
        };
      in {
        # devShells.default = (fhsFn "/usr/bin/env bash").env;
        packages.build = build;
        packages.npm-deps = npm-deps;
        packages.default = pkgs.buildNpmPackage {
          pname = "espoir";
          src = build;
          dontNpmBuild = true;
          version = "0.0.1";
          npmDepsHash = deps-hash;
        };
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.clojure pkgs.nodejs
          ];
        };
      }
    );
}
