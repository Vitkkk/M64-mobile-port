/* Layout de ROM de N64: cabeçalho em 0x0, código a partir de 0x1000 da
 * ROM carregado no ponto de entrada (0x80000400). */
ENTRY(entry_point)
PHDRS {
    header PT_LOAD;
    code PT_LOAD;
}
SECTIONS {
    .header 0xA4000000 : AT(0) { KEEP(*(.header)) } :header
    .text 0x80000400 : AT(0x1000) { KEEP(*(.text.entry)) *(.text*) } :code
    .data : { *(.data*) *(.sdata*) *(.rodata*) } :code
    .bss (NOLOAD) : { *(.bss*) *(.sbss*) *(COMMON) } :code
    /DISCARD/ : { *(.MIPS.abiflags) *(.reginfo) *(.comment) *(.pdr) *(.gnu.attributes) }
}
