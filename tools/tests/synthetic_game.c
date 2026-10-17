/* "Jogo" sintético que usa a API da libultra, para testar o runtime sem ROM
 * comercial: boot -> thread ociosa -> thread principal -> fila de mensagens
 * entre threads -> espera de 3 quadros por eventos de VI -> fim.
 * As funções da libultra estão em synthetic_ultra.c como corpos vazios; o
 * recompilador as troca pelas implementações nativas do runtime, como faz
 * com as do jogo de verdade. */

typedef unsigned long long u64;
typedef void* OSMesg;
typedef struct { u64 data[0x1B0 / 8]; } OSThread;
typedef struct { void* recv; void* send; int valid; int first; int count; OSMesg* msg; } OSMesgQueue;

void osInitialize(void);
void osCreateThread(OSThread* t, int id, void (*entry)(void*), void* arg, void* sp, int pri);
void osStartThread(OSThread* t);
void osCreateMesgQueue(OSMesgQueue* mq, OSMesg* msg, int count);
int osSendMesg(OSMesgQueue* mq, OSMesg msg, int flag);
int osRecvMesg(OSMesgQueue* mq, OSMesg* msg, int flag);
void osCreateViManager(int pri);
void osViSetEvent(OSMesgQueue* mq, OSMesg msg, unsigned retrace);
void osSetThreadPri(OSThread* t, int pri);
void osViSetMode(void* mode);
void m64_test_done(int value);
void m64_test_log(int value);

#define OS_MESG_BLOCK 1

static OSThread idle_thread, main_thread, worker_thread;
static u64 idle_stack[0x200], main_stack[0x400], worker_stack[0x200];
static OSMesgQueue work_queue, vi_queue;
static OSMesg work_msgs[4], vi_msgs[4];
/* OSViMode: tipo + registradores comuns + 2 campos. Zerado basta aqui. */
static struct { unsigned int words[0x48 / 4]; } vi_mode;

static void worker(void* arg) {
    /* Manda 3 mensagens para a thread principal. */
    for (int i = 1; i <= 3; i++) {
        osSendMesg(&work_queue, (OSMesg)(long)(i * 10), OS_MESG_BLOCK);
    }
}

static void main_proc(void* arg) {
    m64_test_log(1);
    osCreateMesgQueue(&work_queue, work_msgs, 4);
    osCreateThread(&worker_thread, 4, worker, 0, worker_stack + 0x200, 5);
    osStartThread(&worker_thread);

    int sum = 0;
    for (int i = 0; i < 3; i++) {
        OSMesg m;
        osRecvMesg(&work_queue, &m, OS_MESG_BLOCK);
        sum += (int)(long)m; /* 10 + 20 + 30 */
    }
    m64_test_log(sum);

    /* Espera 3 quadros pelo VI, como o laço principal de um jogo. */
    osCreateMesgQueue(&vi_queue, vi_msgs, 4);
    osCreateViManager(254);
    osViSetMode(&vi_mode);
    osViSetEvent(&vi_queue, (OSMesg)1, 1);
    for (int i = 0; i < 3; i++) {
        OSMesg m;
        osRecvMesg(&vi_queue, &m, OS_MESG_BLOCK);
        sum += (int)(long)m; /* +1 por quadro */
        m64_test_log(sum);
    }

    m64_test_done(sum); /* esperado: 63 */
}

static void idle(void* arg) {
    osCreateThread(&main_thread, 3, main_proc, 0, main_stack + 0x400, 10);
    osStartThread(&main_thread);
    /* Como no SM64: prioridade 0 e laço infinito. O recompilador traduz o
     * laço em pause_self, que cede a vez às outras threads. */
    osSetThreadPri(0, 0);
    for (;;) {
    }
}

void game_main(void) {
    osInitialize();
    osCreateThread(&idle_thread, 1, idle, 0, idle_stack + 0x200, 100);
    osStartThread(&idle_thread);
}

/* Ponto de entrada em 0x80000400 (ROM 0x1000): configura a pilha, como o
 * código de boot de um jogo, e chama game_main. */
__asm__(
    ".section .text.entry, \"ax\"\n"
    ".globl entry_point\n"
    ".type entry_point, @function\n"
    ".set noreorder\n"
    "entry_point:\n"
    "    lui   $sp, 0x8030\n"
    "    j     game_main\n"
    "    nop\n"
    ".set reorder\n"
    ".size entry_point, . - entry_point\n"
);

/* Cabeçalho mínimo de ROM (magic .z64 + título), em 0x0 da ROM. */
__attribute__((section(".header"), used))
static const unsigned char rom_header[0x40] = {
    0x80, 0x37, 0x12, 0x40,
    [0x20] = 'J', 'O', 'G', 'O', ' ', 'S', 'I', 'N', 'T', 'E', 'T', 'I', 'C', 'O',
};
