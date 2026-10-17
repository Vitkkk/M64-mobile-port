// Testa o recompilador AOT com um programa MIPS sintético escrito à mão
// para este teste (não é código de nenhum jogo).
#include <cstring>
#include <filesystem>
#include <fstream>

#include "m64/aot.hpp"
#include "test.hpp"

namespace {

constexpr uint32_t kRomCodeOffset = 0x1000;
constexpr uint32_t kCodeVram = 0x80000400;

// add5(a0)          -> a0 + 5
// add_twice(a0)     -> add5(add5(a0 + 1))  [jal direto]
// store_add5(a0,a1) -> *a1 = add5(a0)       [jalr, busca por ponteiro]
constexpr uint32_t kProgram[] = {
    // add5 @ 0x80000400
    0x24820005, // addiu v0, a0, 5
    0x03E00008, // jr    ra
    0x00000000, // nop
    0x00000000, // (padding)
    // add_twice @ 0x80000410
    0x27BDFFE8, // addiu sp, sp, -0x18
    0xAFBF0014, // sw    ra, 0x14(sp)
    0x0C000100, // jal   add5
    0x24840001, //  addiu a0, a0, 1
    0x0C000100, // jal   add5
    0x00402025, //  or    a0, v0, zero
    0x8FBF0014, // lw    ra, 0x14(sp)
    0x03E00008, // jr    ra
    0x27BD0018, //  addiu sp, sp, 0x18
    0x00000000, // (padding)
    0x00000000,
    0x00000000,
    // store_add5 @ 0x80000440
    0x27BDFFE8, // addiu sp, sp, -0x18
    0xAFBF0014, // sw    ra, 0x14(sp)
    0xAFA5001C, // sw    a1, 0x1C(sp)
    0x3C198000, // lui   t9, 0x8000
    0x27390400, // addiu t9, t9, 0x400
    0x0320F809, // jalr  t9
    0x00000000, //  nop
    0x8FA5001C, // lw    a1, 0x1C(sp)
    0xACA20000, // sw    v0, 0(a1)
    0x8FBF0014, // lw    ra, 0x14(sp)
    0x03E00008, // jr    ra
    0x27BD0018, //  addiu sp, sp, 0x18
};

std::vector<uint8_t> make_rom() {
    std::vector<uint8_t> rom(0x2000);
    rom[0] = 0x80;
    rom[1] = 0x37;
    rom[2] = 0x12;
    rom[3] = 0x40;
    for (size_t i = 0; i < std::size(kProgram); i++) {
        const uint32_t w = kProgram[i];
        uint8_t* p = &rom[kRomCodeOffset + i * 4];
        p[0] = uint8_t(w >> 24);
        p[1] = uint8_t(w >> 16);
        p[2] = uint8_t(w >> 8);
        p[3] = uint8_t(w);
    }
    return rom;
}

std::filesystem::path write_syms() {
    const auto path = std::filesystem::temp_directory_path() / "m64_test_syms.toml";
    std::ofstream out{path};
    out << "[[section]]\n"
           "name = \".text\"\n"
           "rom = 0x00001000\n"
           "vram = 0x80000400\n"
           "size = 0x70\n"
           "functions = [\n"
           "    { name = \"add5\", vram = 0x80000400, size = 0x10 },\n"
           "    { name = \"add_twice\", vram = 0x80000410, size = 0x30 },\n"
           "    { name = \"store_add5\", vram = 0x80000440, size = 0x30 },\n"
           "]\n";
    return path;
}

} // namespace

TEST(aot_recompiles_and_runs_synthetic_program) {
    const std::vector<uint8_t> rom = make_rom();
    const auto syms = write_syms();

    m64::AotProgram program;
    std::string error;
    const bool ok = m64::recompile_program(rom, syms, program, error);
    if (!ok) std::fprintf(stderr, "  erro: %s\n", error.c_str());
    CHECK(ok);
    if (!ok) return;

    CHECK(program.stats().num_functions == 3);
    CHECK(program.find("add5") == program.find(kCodeVram));

    std::vector<uint8_t> rdram(0x200000);
    auto reset = [&](recomp_context& ctx) {
        ctx = recomp_context{};
        ctx.r29 = 0xFFFFFFFF80000000ull + rdram.size() - 0x10;
    };

    recomp_context ctx;
    reset(ctx);
    ctx.r4 = 10;
    program.find("add5")(rdram.data(), &ctx);
    CHECK(ctx.r2 == 15);

    reset(ctx);
    ctx.r4 = 10;
    program.find("add_twice")(rdram.data(), &ctx);
    CHECK(ctx.r2 == 21);

    reset(ctx);
    ctx.r4 = 37;
    ctx.r5 = 0xFFFFFFFF80100000ull;
    program.find("store_add5")(rdram.data(), &ctx);
    uint32_t stored;
    std::memcpy(&stored, &rdram[0x100000], 4);
    CHECK(stored == 42);

    std::filesystem::remove(syms);
}

TEST(aot_reports_bad_symbol_file) {
    m64::AotProgram program;
    std::string error;
    CHECK(!m64::recompile_program(make_rom(), "/nao/existe.toml", program, error));
    CHECK(!error.empty());
}

namespace {
void native_add100(uint8_t*, recomp_context* ctx) {
    ctx->r2 = ctx->r4 + 100;
}
} // namespace

TEST(aot_native_functions_replace_translated_ones) {
    const auto syms = write_syms();
    m64::AotHooks hooks;
    hooks.native_functions["add5"] = native_add100;

    m64::AotProgram program;
    std::string error;
    const bool ok = m64::recompile_program(make_rom(), syms, hooks, program, error);
    CHECK(ok);
    if (!ok) return;
    CHECK(program.stats().num_native == 1);

    std::vector<uint8_t> rdram(0x200000);
    recomp_context ctx{};
    ctx.r29 = 0xFFFFFFFF80000000ull + rdram.size() - 0x10;

    // Chamada direta à função substituída.
    ctx.r4 = 1;
    program.find("add5")(rdram.data(), &ctx);
    CHECK(ctx.r2 == 101);

    // jal de outra função traduzida: add_twice(x) = add5(add5(x + 1)).
    ctx.r4 = 1;
    program.find("add_twice")(rdram.data(), &ctx);
    CHECK(ctx.r2 == 202);

    // jalr (ponteiro de função) também cai na nativa.
    ctx.r4 = 7;
    ctx.r5 = 0xFFFFFFFF80100000ull;
    program.find("store_add5")(rdram.data(), &ctx);
    uint32_t stored;
    std::memcpy(&stored, &rdram[0x100000], 4);
    CHECK(stored == 107);

    std::filesystem::remove(syms);
}
