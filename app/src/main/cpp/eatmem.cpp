#include <jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace {

struct Block {
    void* ptr;
    size_t size;
};

std::mutex g_mutex;
std::vector<Block> g_blocks;
std::vector<std::thread> g_threads;
std::atomic<bool> g_running{false};
std::atomic<uint64_t> g_allocated_bytes{0};

inline uint64_t xorshift64star(uint64_t& x) {
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    return x * 2685821657736338717ULL;
}

void eat_worker(int tid, size_t chunk_bytes) {
    uint64_t seed = 0x9E3779B97F4A7C15ULL ^ (static_cast<uint64_t>(tid + 1) * 0xBF58476D1CE4E5B9ULL)
                    ^ static_cast<uint64_t>(getpid());
    size_t cur_chunk = chunk_bytes;

    while (g_running.load(std::memory_order_relaxed)) {
        void* p = mmap(nullptr, cur_chunk, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (p == MAP_FAILED) {
            if (cur_chunk > 1024 * 1024) {
                cur_chunk = 1024 * 1024;
                continue;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        uint64_t* words = static_cast<uint64_t*>(p);
        size_t word_count = cur_chunk / sizeof(uint64_t);
        for (size_t i = 0; i < word_count; ++i) {
            if ((i & 0x3FFF) == 0 && !g_running.load(std::memory_order_relaxed)) {
                break;
            }
            words[i] = xorshift64star(seed);
        }

        {
            std::lock_guard<std::mutex> lock(g_mutex);
            g_blocks.push_back(Block{p, cur_chunk});
        }
        g_allocated_bytes.fetch_add(cur_chunk, std::memory_order_relaxed);
    }
}

void join_and_free_all() {
    g_running.store(false, std::memory_order_release);
    for (auto& t : g_threads) {
        if (t.joinable()) t.join();
    }
    g_threads.clear();

    std::lock_guard<std::mutex> lock(g_mutex);
    for (const auto& blk : g_blocks) {
        if (blk.ptr != nullptr && blk.size > 0) munmap(blk.ptr, blk.size);
    }
    g_blocks.clear();
    g_allocated_bytes.store(0, std::memory_order_release);
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_magicalstory_memorykiller17_nativecore_NativeMemoryEater_startEating(
        JNIEnv*, jobject, jint thread_count, jint chunk_mb) {
    join_and_free_all();
    int threads = thread_count > 0 ? thread_count : 6;
    if (threads > 8) threads = 8;
    size_t chunk_bytes = static_cast<size_t>(chunk_mb > 0 ? chunk_mb : 8) * 1024ULL * 1024ULL;
    g_running.store(true, std::memory_order_release);
    for (int i = 0; i < threads; ++i) {
        g_threads.emplace_back(eat_worker, i, chunk_bytes);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_magicalstory_memorykiller17_nativecore_NativeMemoryEater_stopAndRelease(
        JNIEnv*, jobject) {
    join_and_free_all();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_magicalstory_memorykiller17_nativecore_NativeMemoryEater_getAllocatedMb(
        JNIEnv*, jobject) {
    return static_cast<jlong>(g_allocated_bytes.load(std::memory_order_relaxed) / (1024ULL * 1024ULL));
}
