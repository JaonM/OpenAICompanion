fn main() {
    println!("cargo:rerun-if-changed=src/harness.udl");
    uniffi::generate_scaffolding_for_crate("src/harness.udl", "harness")
        .expect("generate UniFFI scaffolding");
}
