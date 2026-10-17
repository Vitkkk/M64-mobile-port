#include "test.hpp"

int g_test_failures = 0;

std::vector<TestCase>& test_registry() {
    static std::vector<TestCase> registry;
    return registry;
}

int main() {
    int failed_tests = 0;
    for (const TestCase& test : test_registry()) {
        const int before = g_test_failures;
        test.fn();
        const bool ok = g_test_failures == before;
        std::printf("[%s] %s\n", ok ? " ok " : "FAIL", test.name);
        failed_tests += ok ? 0 : 1;
    }
    std::printf("%zu testes, %d falharam\n", test_registry().size(), failed_tests);
    return failed_tests == 0 ? 0 : 1;
}
