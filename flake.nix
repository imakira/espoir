{
  description = "Java development environment";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, flake-utils, nixpkgs }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system};
          fhsFn = runScript: pkgs.buildFHSUserEnv {
            name = "espoir";
            targetPkgs = pkgs: [pkgs.gcc pkgs.libz] ;
            runScript = runScript;
          };
      in
      {
        devShells.default = (fhsFn "/usr/bin/env bash").env;
        packages.default = (fhsFn "${pkgs.babashka}/bin/bb ${self}/espoir");
      }
    );
}
