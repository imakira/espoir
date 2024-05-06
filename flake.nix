{
  description = "Java development environment";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, flake-utils, nixpkgs }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system};
          fhs = pkgs.buildFHSUserEnv {
            name = "espoir";
            targetPkgs = pkgs: [pkgs.gcc pkgs.libz] ;
            runScript = "${pkgs.babashka}/bin/bb ./espoir";
          };
      in
      {
        devShells.default = fhs.env;
        packages.default = fhs;
      }
    );
}
