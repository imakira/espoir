
{
  description = "";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, flake-utils, nixpkgs }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        # devShells.default = (fhsFn "/usr/bin/env bash").env;
        packages.default = pkgs.buildNpmPackage rec {
          pname = "espoir";
          version = "0.0.1";
          src = "${self}";
          npmDepsHash = "sha256-oE9fx4IX0NENw1usb9C8fQlbmGwQsPyMpdKwjXAMub8=";
          npmPackFlags = [ "--ignore-scripts" ];
          dontNpmBuild = true;
        };
      }
    );
}
