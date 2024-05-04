{
  description = "Java development environment";
  inputs.flake-utils.url = "github:numtide/flake-utils";
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  outputs = { self, flake-utils, nixpkgs }:
    flake-utils.lib.eachDefaultSystem (system:
      let pkgs = nixpkgs.legacyPackages.${system};
          fhs = pkgs.buildFHSUserEnv {
            name = "fhs-shell";
            targetPkgs = pkgs: [pkgs.gcc pkgs.libtool pkgs.libz] ;
          };
      in
      {
        devShells.default = fhs.env;
      }
    );
}
