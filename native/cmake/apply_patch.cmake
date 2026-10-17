# Aplica um patch (git diff) sobre um submódulo, uma vez. Se já estiver
# aplicado, não faz nada; se não aplicar nem reverter, falha com uma
# mensagem clara (o submódulo provavelmente foi atualizado).
function(m64_apply_patch repo_dir patch_file)
    find_package(Git REQUIRED)
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${repo_dir}" apply --reverse --check "${patch_file}"
        RESULT_VARIABLE already_applied OUTPUT_QUIET ERROR_QUIET)
    if (already_applied EQUAL 0)
        return()
    endif()
    execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${repo_dir}" apply "${patch_file}"
        RESULT_VARIABLE result ERROR_VARIABLE error_text)
    if (NOT result EQUAL 0)
        message(FATAL_ERROR "Não foi possível aplicar ${patch_file} em ${repo_dir}:\n${error_text}")
    endif()
    message(STATUS "m64: patch aplicado: ${patch_file}")
endfunction()
