// The app's Python executable, reached as $HOME/bin/python3 (hermes_android.launcher keeps that symlink).
// Android lets an app exec only what the installer extracted into its native-library directory, so this ships among
// the native libraries as libhermes_pyexec.so. A child the core spawns (a stdio MCP server) gets an env of a few safe
// keys only, so the launcher finds everything from where it lives: the libraries beside it, the runtime directory in
// the .hermes-runtime pointer beside the symlink it was started through.
#include <errno.h>
#include <libgen.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#define PY_SSIZE_T_CLEAN
#include <Python.h>

static int refuse(const char *what, const char *path) {
    fprintf(stderr, "hermes-pyexec: %s %s: %s\n", what, path, strerror(errno));
    return 2;
}

// Whether `list` (a colon-separated path list) starts with the directory `dir`.
static int starts_with_dir(const char *list, const char *dir) {
    size_t n = strlen(dir);
    return list != NULL && strncmp(list, dir, n) == 0 && (list[n] == ':' || list[n] == '\0');
}

static int append_path(PyConfig *config, const char *path) {
    wchar_t *wide = Py_DecodeLocale(path, NULL);
    if (wide == NULL) return -1;
    PyStatus status = PyWideStringList_Append(&config->module_search_paths, wide);
    PyMem_RawFree(wide);
    return PyStatus_Exception(status) ? -1 : 0;
}

static int append_runtime_path(PyConfig *config, const char *runtime, const char *relative) {
    char path[PATH_MAX];
    snprintf(path, sizeof path, "%s/%s", runtime, relative);
    return append_path(config, path);
}

// PYTHONPATH's entries first, as python3 puts them (the config ignores the environment otherwise).
static int append_pythonpath(PyConfig *config) {
    const char *value = getenv("PYTHONPATH");
    if (value == NULL || *value == '\0') return 0;
    char *copy = strdup(value), *rest = copy, *entry;
    if (copy == NULL) return -1;
    int failed = 0;
    while (!failed && (entry = strsep(&rest, ":")) != NULL) {
        if (*entry != '\0') failed = append_path(config, entry);
    }
    free(copy);
    return failed;
}

// The runtime pointer's content, trailing whitespace dropped; NULL (errno set) if it cannot be read or is empty.
static char *read_pointer(const char *pointer, char *runtime, size_t size) {
    FILE *file = fopen(pointer, "re");
    if (file == NULL) return NULL;
    size_t length = fread(runtime, 1, size - 1, file);
    fclose(file);
    while (length > 0 && strchr(" \t\r\n", runtime[length - 1]) != NULL) length--;
    runtime[length] = '\0';
    if (length == 0) {
        errno = ENOENT;
        return NULL;
    }
    return runtime;
}

// Python's directories under the runtime, the app's packages and wheels after them, sys.executable = argv[0].
static PyStatus initialize(const char *runtime, int argc, char **argv) {
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);  // no PYTHON* env, no user site
    config.isolated = 0;                   // but a command line as python3's: -c, -m, a script's own dir first
    config.parse_argv = 1;
    config.safe_path = 0;
    config.install_signal_handlers = 1;
    config.configure_c_stdio = 1;
    config.module_search_paths_set = 1;
    PyStatus status = PyConfig_SetBytesString(&config, &config.home, runtime);
    // Never empty: python.org's _android_support sends an empty-executable process's stdout to logcat.
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesString(&config, &config.executable, argv[0]);
    if (!PyStatus_Exception(status)) status = PyConfig_SetBytesArgv(&config, argc, argv);
    if (!PyStatus_Exception(status)
        && (append_pythonpath(&config) || append_runtime_path(&config, runtime, "lib/python3.14")
            || append_runtime_path(&config, runtime, "lib/python3.14/lib-dynload")
            || append_runtime_path(&config, runtime, "android"))) {
        status = PyStatus_NoMemory();
    }
    if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    return status;
}

// What hermes_android.prepare() adds after the stdlib: the core, then the wheels with their .pth files.
static int add_app_paths(const char *runtime) {
    char app[PATH_MAX], wheels[PATH_MAX];
    snprintf(app, sizeof app, "%s/app", runtime);
    snprintf(wheels, sizeof wheels, "%s/site-packages", runtime);
    PyObject *path = PySys_GetObject("path"), *entry = PyUnicode_DecodeFSDefault(app);
    int failed = path == NULL || entry == NULL || PyList_Append(path, entry) != 0;
    Py_XDECREF(entry);
    PyObject *site = failed ? NULL : PyImport_ImportModule("site");
    PyObject *done = site ? PyObject_CallMethod(site, "addsitedir", "s", wheels) : NULL;
    failed = done == NULL;
    Py_XDECREF(done);
    Py_XDECREF(site);
    if (failed) PyErr_Print();
    return failed;
}

int main(int argc, char **argv) {
    if (argc < 1 || argv[0] == NULL) {
        fputs("hermes-pyexec: started with no argv[0]\n", stderr);
        return 2;
    }
    // 1. Extensions dlopened later resolve their libraries (libssl_python.so, lib*_hermes.so) by soname alone, through
    //    LD_LIBRARY_PATH, which the linker reads once at exec: put this directory first and exec again, once.
    char self[PATH_MAX], copy[PATH_MAX], dir[PATH_MAX];
    if (realpath("/proc/self/exe", self) == NULL) return refuse("cannot resolve", "/proc/self/exe");
    snprintf(copy, sizeof copy, "%s", self);
    snprintf(dir, sizeof dir, "%s", dirname(copy));
    const char *search = getenv("LD_LIBRARY_PATH");
    if (!starts_with_dir(search, dir)) {
        char value[PATH_MAX * 4];
        snprintf(value, sizeof value, "%s%s%s", dir, search && *search ? ":" : "", search && *search ? search : "");
        if (setenv("LD_LIBRARY_PATH", value, 1) != 0) return refuse("cannot set LD_LIBRARY_PATH for", self);
        execv(self, argv);  // argv[0] unchanged: the symlink the spawner named
        return refuse("cannot re-exec", self);
    }
    // 2. The runtime directory, from the pointer beside the symlink this was started through.
    char where[PATH_MAX], pointer[PATH_MAX], runtime[PATH_MAX];
    snprintf(where, sizeof where, "%s", argv[0]);
    snprintf(pointer, sizeof pointer, "%s/.hermes-runtime", dirname(where));
    if (read_pointer(pointer, runtime, sizeof runtime) == NULL) return refuse("cannot read the runtime pointer", pointer);
    // 3. What the app's interpreter has, unless the spawner chose otherwise.
    char certs[PATH_MAX];
    snprintf(certs, sizeof certs, "%s/site-packages/certifi/cacert.pem", runtime);
    setenv("SSL_CERT_FILE", certs, 0);
    setenv("CRYPTOGRAPHY_OPENSSL_NO_LEGACY", "1", 0);
    PyStatus status = initialize(runtime, argc, argv);
    if (PyStatus_Exception(status)) Py_ExitStatusException(status);
    if (add_app_paths(runtime)) {
        Py_Finalize();
        return 1;
    }
    // 4. -c, -m or a script, as python3 runs them.
    return Py_RunMain();
}
