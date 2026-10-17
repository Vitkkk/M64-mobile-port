#pragma once

#include <cstdio>
#include <functional>
#include <string>
#include <vector>

struct TestCase {
    const char* name;
    std::function<void()> fn;
};

std::vector<TestCase>& test_registry();
extern int g_test_failures;

struct TestRegistrar {
    TestRegistrar(const char* name, std::function<void()> fn) { test_registry().push_back({name, std::move(fn)}); }
};

#define TEST(name)                                         \
    static void name();                                    \
    static TestRegistrar name##_registrar{#name, name};    \
    static void name()

#define CHECK(cond)                                                                \
    do {                                                                           \
        if (!(cond)) {                                                             \
            std::fprintf(stderr, "  falhou: %s (%s:%d)\n", #cond, __FILE__, __LINE__); \
            g_test_failures++;                                                     \
        }                                                                          \
    } while (0)
