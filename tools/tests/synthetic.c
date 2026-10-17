/* Programa sintético para o teste de ponta a ponta do pipeline
 * ELF -> tabela de símbolos -> recompilação AOT. Não tem relação com
 * nenhum jogo. */

int add5(int x) {
    return x + 5;
}

int fib(int n) {
    int a = 0, b = 1;
    for (int i = 0; i < n; i++) {
        int t = a + b;
        a = b;
        b = t;
    }
    return a;
}

typedef int (*op_t)(int);
static op_t ops[2] = { add5, fib };

int entry(int n) {
    /* Chamada direta + chamada por ponteiro (jalr). */
    return fib(n) + ops[n & 1](n);
}

/* Cabeçalho mínimo de ROM (magic .z64 + título), em 0x0 da ROM. */
__attribute__((section(".header"), used))
static const unsigned char rom_header[0x40] = {
    0x80, 0x37, 0x12, 0x40,
    [0x20] = 'R', 'O', 'M', ' ', 'S', 'I', 'N', 'T', 'E', 'T', 'I', 'C', 'A',
};
