// JNI glue for the embedded CPython, modelled on CPython's Android testbed (Android/testbed).
// Frames cross as UTF-8 byte arrays: JNI's NewStringUTF takes *modified* UTF-8, which has no
// 4-byte sequences, so emoji in a frame would be corrupted or abort under CheckJNI.
#include <jni.h>
#include <pthread.h>
#include <string.h>
#define PY_SSIZE_T_CLEAN
#include <Python.h>

static JavaVM *vm;
static jclass bridge;          // com.nousresearch.hermes.bridge.EmbeddedPython
static jmethodID on_frame;     // static boolean onFrame(byte[])
static jmethodID read_secrets; // static byte[] readSecrets()
static pthread_key_t attached; // set on threads this file attached; its destructor detaches them
static PyObject *bootstrap;    // the hermes_android module

JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
    (void)reserved;
    vm = jvm;
    return JNI_VERSION_1_6;
}

static void detach(void *env) {
    (void)env;
    (*vm)->DetachCurrentThread(vm);  // ART aborts if a native thread exits still attached
}

static JNIEnv *jni_env(void) {
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*vm)->AttachCurrentThreadAsDaemon(vm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(attached, env);
    return env;
}

// _hermes_native.emit(line) -> bool: hand one frame to the app; False means the app is gone.
static PyObject *native_emit(PyObject *self, PyObject *line) {
    (void)self;
    Py_ssize_t size;
    const char *utf8 = PyUnicode_AsUTF8AndSize(line, &size);
    if (utf8 == NULL) return NULL;
    jboolean delivered = JNI_FALSE;
    Py_BEGIN_ALLOW_THREADS
    JNIEnv *env = jni_env();
    jbyteArray bytes = env ? (*env)->NewByteArray(env, (jsize)size) : NULL;
    if (bytes != NULL) {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)size, (const jbyte *)utf8);
        delivered = (*env)->CallStaticBooleanMethod(env, bridge, on_frame, bytes);
        (*env)->DeleteLocalRef(env, bytes);
    }
    if (env != NULL && (*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        delivered = JNI_FALSE;
    }
    Py_END_ALLOW_THREADS
    return PyBool_FromLong(delivered);
}

// _hermes_native.secrets() -> str: the app's keystore secrets as a JSON object of variable name to value.
static PyObject *native_secrets(PyObject *self, PyObject *unused) {
    (void)self;
    (void)unused;
    JNIEnv *env;
    jbyteArray bytes = NULL;
    Py_BEGIN_ALLOW_THREADS
    env = jni_env();
    if (env != NULL) {
        bytes = (*env)->CallStaticObjectMethod(env, bridge, read_secrets);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);  // never described: the message could carry what was being read
            bytes = NULL;
        }
    }
    Py_END_ALLOW_THREADS
    if (bytes == NULL) {
        PyErr_SetString(PyExc_RuntimeError, "the app could not read its keystore");
        return NULL;
    }
    jsize size = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    PyObject *text = PyUnicode_DecodeUTF8((const char *)data, size, "strict");
    memset(data, 0, (size_t)size);  // the plaintext lives on in Python only, not in the JVM heap
    (*env)->ReleaseByteArrayElements(env, bytes, data, 0);
    (*env)->DeleteLocalRef(env, bytes);
    return text;
}

static PyMethodDef native_methods[] = {
    {"emit", native_emit, METH_O, "Hand one serialized frame to the app."},
    {"secrets", native_secrets, METH_NOARGS, "The app's keystore secrets, as JSON."},
    {NULL, NULL, 0, NULL},
};
static struct PyModuleDef native_module = {PyModuleDef_HEAD_INIT, "_hermes_native", NULL, -1, native_methods,
                                           NULL, NULL, NULL, NULL};
static PyObject *init_native(void) { return PyModule_Create(&native_module); }

// Throws a RuntimeException carrying the pending Python exception's traceback, and clears it.
static void throw_python(JNIEnv *env, const char *what) {
    PyObject *exc = PyErr_GetRaisedException();
    PyObject *text = NULL, *traceback = PyImport_ImportModule("traceback");
    if (traceback != NULL && exc != NULL) {
        PyObject *lines = PyObject_CallMethod(traceback, "format_exception", "O", exc);
        PyObject *empty = PyUnicode_FromString("");
        if (lines != NULL && empty != NULL) text = PyUnicode_Join(empty, lines);
        Py_XDECREF(lines);
        Py_XDECREF(empty);
    }
    const char *detail = text ? PyUnicode_AsUTF8(text) : NULL;
    char message[4096];
    snprintf(message, sizeof message, "%s: %s", what, detail ? detail : "(no traceback)");
    PyErr_Clear();
    Py_XDECREF(text);
    Py_XDECREF(traceback);
    Py_XDECREF(exc);
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), message);
}

static int append_path(PyConfig *config, const char *runtime, const char *relative) {
    char path[PATH_MAX];
    snprintf(path, sizeof path, "%s/%s", runtime, relative);
    wchar_t *wide = Py_DecodeLocale(path, NULL);
    if (wide == NULL) return -1;
    PyStatus status = PyWideStringList_Append(&config->module_search_paths, wide);
    PyMem_RawFree(wide);
    return PyStatus_Exception(status) ? -1 : 0;
}

static PyStatus initialize(const char *runtime) {
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);  // no PYTHON* env, no user site, no signal handlers
    PyStatus status = PyConfig_SetBytesString(&config, &config.home, runtime);
    config.module_search_paths_set = 1;
    if (!PyStatus_Exception(status)
        && (append_path(&config, runtime, "lib/python3.14") || append_path(&config, runtime, "lib/python3.14/lib-dynload")
            || append_path(&config, runtime, "android"))) {
        status = PyStatus_NoMemory();
    }
    if (!PyStatus_Exception(status)) status = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    return status;
}

JNIEXPORT void JNICALL Java_com_nousresearch_hermes_bridge_EmbeddedPython_nativeStart(
        JNIEnv *env, jclass cls, jstring runtime, jstring home, jstring hermes_home, jstring tmp) {
    bridge = (*env)->NewGlobalRef(env, cls);
    on_frame = (*env)->GetStaticMethodID(env, cls, "onFrame", "([B)Z");
    read_secrets = (*env)->GetStaticMethodID(env, cls, "readSecrets", "()[B");
    if (on_frame == NULL || read_secrets == NULL || pthread_key_create(&attached, detach) != 0) return;
    const char *r = (*env)->GetStringUTFChars(env, runtime, NULL), *h = (*env)->GetStringUTFChars(env, home, NULL);
    const char *hh = (*env)->GetStringUTFChars(env, hermes_home, NULL), *t = (*env)->GetStringUTFChars(env, tmp, NULL);
    PyImport_AppendInittab("_hermes_native", init_native);
    PyStatus status = initialize(r);
    if (PyStatus_Exception(status)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"),
                         status.err_msg ? status.err_msg : "Py_InitializeFromConfig failed");
    } else {
        PyObject *paths = Py_BuildValue("{s:s,s:s,s:s,s:s}", "runtime", r, "home", h, "hermes_home", hh, "tmp", t);
        PyObject *native = PyImport_ImportModule("_hermes_native");
        bootstrap = PyImport_ImportModule("hermes_android");
        PyObject *done = paths && native && bootstrap ? PyObject_CallMethod(bootstrap, "start", "OO", paths, native) : NULL;
        if (done == NULL) throw_python(env, "hermes_android.start failed");
        Py_XDECREF(done);
        Py_XDECREF(native);
        Py_XDECREF(paths);
        PyEval_SaveThread();  // the GIL is free between bridge calls; core threads take it as they run
    }
    (*env)->ReleaseStringUTFChars(env, runtime, r);
    (*env)->ReleaseStringUTFChars(env, home, h);
    (*env)->ReleaseStringUTFChars(env, hermes_home, hh);
    (*env)->ReleaseStringUTFChars(env, tmp, t);
}

static PyObject *decode(JNIEnv *env, jbyteArray bytes) {
    jsize size = (*env)->GetArrayLength(env, bytes);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    PyObject *text = PyUnicode_DecodeUTF8((const char *)data, size, "strict");
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    return text;
}

JNIEXPORT void JNICALL Java_com_nousresearch_hermes_bridge_EmbeddedPython_nativeSubmit(
        JNIEnv *env, jclass cls, jbyteArray frame) {
    (void)cls;
    PyGILState_STATE gil = PyGILState_Ensure();
    PyObject *line = decode(env, frame);
    PyObject *done = line ? PyObject_CallMethod(bootstrap, "submit", "O", line) : NULL;
    if (done == NULL) PyErr_Print();  // the transport answers protocol errors itself; this is a bridge bug
    Py_XDECREF(done);
    Py_XDECREF(line);
    PyGILState_Release(gil);
}

JNIEXPORT jbyteArray JNICALL Java_com_nousresearch_hermes_bridge_EmbeddedPython_nativeCall(
        JNIEnv *env, jclass cls, jstring function, jbyteArray arg) {
    (void)cls;
    PyGILState_STATE gil = PyGILState_Ensure();
    const char *name = (*env)->GetStringUTFChars(env, function, NULL);
    PyObject *text = decode(env, arg);
    PyObject *result = text ? PyObject_CallMethod(bootstrap, name, "O", text) : NULL;
    Py_ssize_t size;
    const char *utf8 = result ? PyUnicode_AsUTF8AndSize(result, &size) : NULL;
    jbyteArray out = NULL;
    if (utf8 == NULL) {
        throw_python(env, name);
    } else if ((out = (*env)->NewByteArray(env, (jsize)size)) != NULL) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)size, (const jbyte *)utf8);
    }
    Py_XDECREF(result);
    Py_XDECREF(text);
    (*env)->ReleaseStringUTFChars(env, function, name);
    PyGILState_Release(gil);
    return out;
}
