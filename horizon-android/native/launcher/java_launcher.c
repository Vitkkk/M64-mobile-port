/*
 * O `java` e o `javac` que o horizon-prepare roda no celular para preparar os recursos do jogo.
 *
 * Um app Android (targetSdk >= 29) só pode executar programas da pasta de bibliotecas do APK,
 * nunca um binário baixado. Por isso este programa vai no APK duas vezes, como
 * libzenith_java.so e libzenith_javac.so (compilado com -DJAVAC), e o Zenith passa os caminhos
 * ao horizon-prepare (HORIZON_PREPARE_JAVA / HORIZON_PREPARE_JAVAC, patch do Zenith).
 *
 * Cada um carrega o runtime OpenJDK 25 para Android que o Zenith baixou (ZENITH_JAVA_HOME) e o
 * inicia como os lançadores do próprio JDK fazem: JLI_Launch da libjli.so. O runtime acha a
 * própria pasta pelo caminho dessa biblioteca. Técnica do Starling (starling-client/starling,
 * MIT), reescrita aqui.
 */
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef int (*jli_launch_fn)(int argc, char **argv, int jargc, const char **jargv, int appclassc,
                             const char **appclassv, const char *fullversion, const char *dotversion,
                             const char *pname, const char *lname, unsigned char javaargs,
                             unsigned char cpwildcard, unsigned char javaw, int ergo);

static void *load(const char *path) {
    void *handle = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        fprintf(stderr, "zenith java: %s\n", dlerror());
    }
    return handle;
}

int main(int argc, char **argv) {
    const char *home = getenv("ZENITH_JAVA_HOME");
    if (home == NULL || home[0] == '\0') {
        fprintf(stderr, "zenith java: ZENITH_JAVA_HOME is not set\n");
        return 2;
    }
    char path[PATH_MAX];

    /* As bibliotecas do runtime precisam da libc++_shared.so e não a trazem: ela está ao lado
       deste programa, e carregada antes responde à dependência delas pelo nome. */
    ssize_t length = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (length <= 0) {
        fprintf(stderr, "zenith java: cannot find its own folder\n");
        return 2;
    }
    path[length] = '\0';
    char *slash = strrchr(path, '/');
    if (slash == NULL || (size_t)(slash - path) + sizeof("/libc++_shared.so") > sizeof(path)) {
        return 2;
    }
    strcpy(slash, "/libc++_shared.so");
    if (load(path) == NULL) {
        return 2;
    }

    if (snprintf(path, sizeof(path), "%s/lib/libjli.so", home) >= (int)sizeof(path)) {
        return 2;
    }
    void *jli = load(path);
    if (jli == NULL) {
        return 2;
    }
    jli_launch_fn launch = (jli_launch_fn)dlsym(jli, "JLI_Launch");
    if (launch == NULL) {
        fprintf(stderr, "zenith java: %s has no JLI_Launch\n", path);
        return 2;
    }
#ifdef JAVAC
    /* Como o lançador javac do JDK: o módulo do compilador, opções -J para o runtime. */
    static const char *javac_args[] = {"-J-ms8m", "-m", "jdk.compiler/com.sun.tools.javac.Main"};
    return launch(argc, argv, 3, javac_args, 0, NULL, "25", "25", "javac", "javac", 1, 1, 0, 0);
#else
    return launch(argc, argv, 0, NULL, 0, NULL, "25", "25", "java", "java", 0, 1, 0, 0);
#endif
}
