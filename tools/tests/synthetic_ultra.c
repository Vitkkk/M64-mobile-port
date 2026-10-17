/* Corpos vazios com os nomes das funções da libultra usadas pelo jogo
 * sintético. Ficam num arquivo separado para o compilador não removê-los;
 * o recompilador os substitui pelas implementações nativas do runtime. */

void osInitialize(void) {}
void osCreateThread(void* t, int id, void* entry, void* arg, void* sp, int pri) {}
void osStartThread(void* t) {}
void osCreateMesgQueue(void* mq, void* msg, int count) {}
int osSendMesg(void* mq, void* msg, int flag) { return 0; }
int osRecvMesg(void* mq, void* msg, int flag) { return 0; }
void osCreateViManager(int pri) {}
void osViSetEvent(void* mq, void* msg, unsigned retrace) {}
void osSetThreadPri(void* t, int pri) {}
void osViSetMode(void* mode) {}
void m64_test_done(int value) {}
void m64_test_log(int value) {}
