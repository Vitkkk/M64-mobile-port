/* ROM sintética no mesmo formato que a decompilação produz: o binário
 * "cru" do ELF é a ROM. Cabeçalho em 0x0, código em 0x1000 da ROM,
 * carregado em 0x80000400. */
ENTRY(entry)
PHDRS {
    header PT_LOAD;
    code PT_LOAD;
}
SECTIONS {
    .header 0xA4000000 : AT(0) { KEEP(*(.header)) } :header
    .text 0x80000400 : AT(0x1000) { *(.text*) } :code
    .data : { *(.data*) *(.sdata*) *(.rodata*) } :code
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.comment) *(.pdr) *(.gnu.attributes) }
}
