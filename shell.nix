{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  buildInputs = [
    pkgs.graalvmPackages.graalvm-ce
  ];

  JAVA_HOME = "${pkgs.graalvmPackages.graalvm-ce}";
  GRAALVM_HOME = "${pkgs.graalvmPackages.graalvm-ce}";
}
