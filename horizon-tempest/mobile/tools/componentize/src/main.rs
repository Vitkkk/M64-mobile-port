//! `tempest-componentize <core.wasm> <out.wasm>`
fn main() -> Result<(), String> {
    let args: Vec<String> = std::env::args().collect();
    let [_, input, output] = args.as_slice() else {
        return Err("usage: tempest-componentize <core.wasm> <out.wasm>".into());
    };
    let core = std::fs::read(input).map_err(|e| e.to_string())?;
    let component = wit_component::ComponentEncoder::default()
        .module(&core)
        .and_then(|e| e.validate(true).encode())
        .map_err(|e| format!("{e:#}"))?;
    if let wit_component::DecodedWasm::Component(resolve, world) =
        wit_component::decode(&component).map_err(|e| format!("{e:#}"))?
    {
        for (key, _) in &resolve.worlds[world].imports {
            println!("import {}", resolve.name_world_key(key));
        }
        for (key, _) in &resolve.worlds[world].exports {
            println!("export {}", resolve.name_world_key(key));
        }
    }
    std::fs::write(output, &component).map_err(|e| e.to_string())?;
    println!("{output}: {} bytes", component.len());
    Ok(())
}
