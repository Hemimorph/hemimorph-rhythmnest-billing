{
  description = "Kotlin development environment with IntelliJ IDEA";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config.allowUnfreePredicate = pkg: nixpkgs.lib.getName pkg == "idea";
      };
    in
    {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          jdk21
          kotlin
          gradle
          jetbrains.idea
        ];

        JAVA_HOME = "${pkgs.jdk21}";
      };
    };
}
