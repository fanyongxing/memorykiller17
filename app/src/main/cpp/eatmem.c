#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>

#define MAX_THREADS 8
#define MAX_BLOCKS 8192

typedef struct {
    void* ptr;
    size_t size;
} MemBlock;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static MemBlock g_blocks[MAX_BLOCKS];
static int g_block_count = 0;
static pthread_t g_threads[MAX_THREADS];
static int g_thread_count = 0;
static atomic_int g_running = 0;
static atomic_uint_least64_t g_allocated_bytes = 0;

typedef struct {
    int tid;
    size_t chunk_bytes;
} WorkerArg;

static WorkerArg g_args[MAX_THREADS];

static inline uint64_t xorshift64star(uint64_t* x) {
    uint64_t v = *x;
    v ^= v >> 12;
    v ^= v << 25;
    v ^= v >> 27;
    *x = v;
    return v * 2685821657736338717ULL;
}

static void* eat_worker(void* arg) {
    WorkerArg* w = (WorkerArg*)arg;
    uint64_t seed = 0x9E3779B97F4A7C15ULL ^ ((uint64_t)(w->tid + 1) * 0xBF58476D1CE4E5B9ULL)
                    ^ (uint64_t)getpid();
    size_t cur_chunk = w->chunk_bytes;

    while (atomic_load_explicit(&g_running, memory_order_relaxed)) {
        void* p = mmap(NULL, cur_chunk, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) {
            if (cur_chunk > 1024 * 1024) {
                cur_chunk = 1024 * 1024;
                continue;
            }
            usleep(10000);
            continue;
        }

        uint64_t* words = (uint64_t*)p;
        size_t count = cur_chunk / sizeof(uint64_t);
        for (size_t i = 0; i < count; ++i) {
            if ((i & 0x3FFF) == 0 && !atomic_load_explicit(&g_running, memory_order_relaxed)) {
                break;
            }
            words[i] = xorshift64star(&seed);
        }

        pthread_mutex_lock(&g_lock);
        if (g_block_count < MAX_BLOCKS) {
            g_blocks[g_block_count].ptr = p;
            g_blocks[g_block_count].size = cur_chunk;
            g_block_count++;
        }
        pthread_mutex_unlock(&g_lock);

        atomic_fetch_add_explicit(&g_allocated_bytes, cur_chunk, memory_order_relaxed);
    }
    return NULL;
}

static void stop_and_free(void) {
    atomic_store_explicit(&g_running, 0, memory_order_release);
    for (int i = 0; i < g_thread_count; ++i) {
        pthread_join(g_threads[i], NULL);
    }
    g_thread_count = 0;

    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_block_count; ++i) {
        if (g_blocks[i].ptr && g_blocks[i].size) {
            munmap(g_blocks[i].ptr, g_blocks[i].size);
        }
    }
    g_block_count = 0;
    pthread_mutex_unlock(&g_lock);
    atomic_store_explicit(&g_allocated_bytes, 0, memory_order_release);
}

JNIEXPORT void JNICALL
Java_com_magicalstory_memorykiller17_nativecore_NativeMemoryEater_startEating(
        JNIEnv* env, jobject thiz, jint thread_count, jint chunk_mb) {
    (void)env;
    (void)thiz;
    stop_and_free();
    int n = thread_count > 0 ? thread_count : 6;
    if (n > MAX_THREADS) n = MAX_THREADS;
    size_t chunk_bytes = (size_t)(chunk_mb > 0 ? chunk_mb : 8) * 1024ULL * 1024ULL;

    atomic_store_explicit(&g_running, 1, memory_order_release);
    for (int i = 0; i < n; ++i) {
        g_args[i].tid = i;
        g_args[i].chunk_bytes = chunk_bytes;
        if (pthread_create(&g_threads[g_thread_count], NULL, eat_worker, &g_args[i]) == 0) {
            g_thread_count++;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_magicalstory_memorykiller17_nativecore_NativeMemoryEater_stopAndRelease(
        JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    stop_and_free();
}

JNIEXPORT jlong JNICALL
Java_com_magicalstory_memorykiller17_nativecore_NativeMemoryEater_getAllocatedMb(
        JNIEnv* env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jlong)(atomic_load_explicit(&g_allocated_bytes, memory_order_relaxed) / (1024ULL * 1024ULL));
}
