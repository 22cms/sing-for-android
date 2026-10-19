// Vi-NNet native engine: executes the compiled 187-op plan (vinet.bin)
// with persistent state buffers, zero graph glue, multithreaded.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <string>
#include <vector>
#include <functional>
#include <atomic>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <algorithm>
#include <chrono>

#if defined(__linux__)
#include <sched.h>
#include <pthread.h>
#endif
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {

inline uint64_t now_ns() {
  return (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
      std::chrono::steady_clock::now().time_since_epoch()).count();
}

static inline void cpu_relax() {
#if defined(__aarch64__) || defined(__arm__)
  asm volatile("yield");
#elif defined(__x86_64__) || defined(__i386__)
  asm volatile("pause");
#endif
}

struct Pool {
  int nw = 0;
  std::vector<int> aff;
  std::vector<std::thread> th;
  std::atomic<uint64_t> gen{0};
  std::atomic<int> done{0};
  std::atomic<bool> stop{false};
  const std::function<void(int,int)>* job = nullptr;
  std::mutex mx;
  std::condition_variable cv;

  Pool(int workers, std::vector<int> affinity) : nw(workers), aff(std::move(affinity)) {
    for (int i = 0; i < nw; i++)
      th.emplace_back([this, i] { pin_thread(i + 1); worker(i); });
  }
  void pin_thread(int idx) {
#if defined(__linux__)
    if (aff.empty()) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    CPU_SET(aff[idx % aff.size()], &set);
    sched_setaffinity(0, sizeof(set), &set);
#endif
  }
  ~Pool() {
    { std::lock_guard<std::mutex> lk(mx); stop.store(true); }
    cv.notify_all();
    for (auto& t : th) t.join();
  }
  void run(const std::function<void(int,int)>& f) {
    if (nw == 0) { f(0, 1); return; }
    job = &f;
    done.store(0, std::memory_order_relaxed);
    { std::lock_guard<std::mutex> lk(mx); gen.fetch_add(1, std::memory_order_release); }
    cv.notify_all();
    f(0, nw + 1);   // main thread takes split 0
    while (done.load(std::memory_order_acquire) != nw) cpu_relax();
  }
  void worker(int tid) {
    uint64_t last = 0;
    for (;;) {
      uint64_t g = gen.load(std::memory_order_acquire);
      if (g == last) {
        // timed spin: covers intra-block dispatch gaps (~50-500 us), then parks
        // so idle workers sleep between cadence calls instead of burning cores
        const auto t0 = std::chrono::steady_clock::now();
        int it = 0;
        for (;;) {
          cpu_relax();
          if ((++it & 127) == 0) {
            if (stop.load(std::memory_order_relaxed)) return;
            g = gen.load(std::memory_order_acquire);
            if (g != last) break;
            if (std::chrono::steady_clock::now() - t0 > std::chrono::milliseconds(2)) break;
          }
        }
        if (g == last) {
          std::unique_lock<std::mutex> lk(mx);
          cv.wait(lk, [&] { return stop.load(std::memory_order_relaxed) ||
                                   gen.load(std::memory_order_acquire) != last; });
          if (stop.load(std::memory_order_relaxed)) return;
          g = gen.load(std::memory_order_acquire);
        }
      }
      last = g;
      (*job)(tid + 1, nw + 1);   // main thread takes split 0
      done.fetch_add(1, std::memory_order_release);
    }
  }
};

static inline void split_range(int total, int tid, int tn, int* r0, int* r1) {
  int chunk = (total + tn - 1) / tn;
  *r0 = std::min(total, tid * chunk);
  *r1 = std::min(total, *r0 + chunk);
}

#pragma pack(push, 1)
struct Op {
  uint16_t type, flags, in0, in1, out, state, w, bias, gamma, beta, u0, u1, d0, d1, d2, d3;
  float f0, f1;
  uint32_t r0;
};
#pragma pack(pop)

struct Vinet {
  std::vector<float> storage;        // raw file
  std::vector<const float*> wptr;
  std::vector<size_t> wsize;
  std::vector<float*> slot;
  std::vector<uint32_t> slot_size;
  std::vector<uint8_t> slot_is_state;
  std::vector<Op> ops;
  int audio_slot = -1, out_slot = -1;
  int nthreads = 4;
  Pool* pool = nullptr;
  std::vector<float> stat_scratch;   // nthreads * 2 * 256
  static constexpr int SCRATCH_STRIDE = 1024;

  ~Vinet() { delete pool; }

  bool load(const char* path);

  void init_threads(int workers, const std::vector<int>& affinity);

  void reset_states() {
    for (size_t i = 0; i < slot.size(); i++)
      if (slot_is_state[i]) memset(slot[i], 0, slot_size[i] * 4);
  }

  void run_block(const float* in, float* out);

  // ---- kernels
  void k_stem(const Op& o, int tid, int tn);
  void k_stem_state(const Op& o);
  void k_gemm(const Op& o, int tid, int tn);
  void k_dwstate(const Op& o, int tid, int tn);
  void k_norm1(const Op& o, int tid, int tn);
  void k_norm2(const Op& o, int tid, int tn);
  void k_addstate(const Op& o, int tid, int tn);
  void k_add(const Op& o, int tid, int tn);
  void k_maskconv(const Op& o, int tid, int tn);
  void k_sigmoid(const Op& o, int tid, int tn);
  void k_maskmul(const Op& o, int tid, int tn);
  void k_deconv(const Op& o, int tid, int tn);
};

bool Vinet::load(const char* path) {
  FILE* f = fopen(path, "rb");
  if (!f) { fprintf(stderr, "cannot open %s\n", path); return false; }
  fseek(f, 0, SEEK_END);
  long sz = ftell(f);
  fseek(f, 0, SEEK_SET);
  storage.resize(sz);
  if (fread(storage.data(), 1, sz, f) != (size_t)sz) { fclose(f); return false; }
  fclose(f);

  const uint8_t* p = (const uint8_t*)storage.data();
  uint32_t magic, ver, nw, nslots, nops, audio, out;
  memcpy(&magic, p, 4); memcpy(&ver, p+4, 4); memcpy(&nw, p+8, 4);
  memcpy(&nslots, p+12, 4); memcpy(&nops, p+16, 4);
  memcpy(&audio, p+20, 4); memcpy(&out, p+24, 4);
  if (magic != 0x54454e56) { fprintf(stderr, "bad magic\n"); return false; }  // 'VNET'
  audio_slot = audio; out_slot = out;
  size_t off = 28;

  std::vector<uint32_t> wid(nw), wlen(nw);
  for (uint32_t i = 0; i < nw; i++) {
    memcpy(&wid[i], p + off, 4); memcpy(&wlen[i], p + off + 4, 4);
    off += 8;
  }
  wptr.resize(nw); wsize.resize(nw);
  const float* wbase = (const float*)(p + off);
  size_t woff = 0;
  for (uint32_t i = 0; i < nw; i++) {
    wptr[i] = wbase + woff;
    wsize[i] = wlen[i];
    woff += wlen[i];
  }
  off += woff * 4;

  slot.resize(nslots); slot_size.resize(nslots); slot_is_state.resize(nslots);
  for (uint32_t i = 0; i < nslots; i++) {
    uint32_t s; uint8_t st;
    memcpy(&s, p + off, 4); memcpy(&st, p + off + 4, 1);
    off += 8;
    slot_size[i] = s; slot_is_state[i] = st;
    void* q = nullptr;
    if (posix_memalign(&q, 64, ((size_t)s * 4 + 63) & ~63ull) != 0) return false;
    slot[i] = (float*)q;
    memset(slot[i], 0, (size_t)s * 4);
  }

  ops.resize(nops);
  for (uint32_t i = 0; i < nops; i++) {
    Op& o = ops[i];
    memcpy(&o, p + off, 44);
    off += 44;
  }
  fprintf(stderr, "loaded: %u weights, %u slots, %u ops\n", nw, nslots, nops);
  return true;
}

void Vinet::init_threads(int workers, const std::vector<int>& affinity) {
  nthreads = workers + 1;
  stat_scratch.assign((size_t)(workers + 1) * 2 * 256, 0.f);
  delete pool;
  pool = new Pool(workers, affinity);
  pool->pin_thread(0);   // main thread
}

static inline float leaky(float v, float alpha) { return v < 0.f ? alpha * v : v; }

void Vinet::k_gemm(const Op& o, int tid, int tn) {
  const int B = o.d0, cin = o.d1, T = o.d3, CO = o.u0;
  const int ep = o.u1; const float alpha = o.f0;
  const float* x = slot[o.in0];
  float* y = slot[o.out];
  const float* W = wptr[o.w];
  const float* bias = wptr[o.bias];
  const int rows_all = B * CO;
  int r0, r1; split_range(rows_all, tid, tn, &r0, &r1);
  for (int b = 0; b < B; b++) {
    int ms = std::max(0, r0 - b * CO), me = std::min(CO, r1 - b * CO);
    if (ms >= me) continue;
    const float* xb = x + (size_t)b * cin * T;
    for (int m0 = ms; m0 < me; m0 += 4) {
      const int cnt = std::min(4, me - m0);
      const float* wp = W + m0;
#if defined(__aarch64__)
      if (cnt == 4) {
        const float32x4_t vs0 = vdupq_n_f32(bias[m0]);
        const float32x4_t vs1 = vdupq_n_f32(bias[m0+1]);
        const float32x4_t vs2 = vdupq_n_f32(bias[m0+2]);
        const float32x4_t vs3 = vdupq_n_f32(bias[m0+3]);
        const float32x4_t vz = vdupq_n_f32(0.f), va = vdupq_n_f32(alpha);
        for (int t0 = 0; t0 + 16 <= T; t0 += 16) {
          float32x4_t a0[4], a1[4], a2[4], a3[4];
          a0[0]=vs0; a0[1]=vs0; a0[2]=vs0; a0[3]=vs0;
          a1[0]=vs1; a1[1]=vs1; a1[2]=vs1; a1[3]=vs1;
          a2[0]=vs2; a2[1]=vs2; a2[2]=vs2; a2[3]=vs2;
          a3[0]=vs3; a3[1]=vs3; a3[2]=vs3; a3[3]=vs3;
          for (int k = 0; k < cin; k++) {
            const float* xk = xb + (size_t)k * T + t0;
            const float32x4_t x0 = vld1q_f32(xk), x1 = vld1q_f32(xk + 4),
                               x2 = vld1q_f32(xk + 8), x3 = vld1q_f32(xk + 12);
            const float32x4_t wv = vld1q_f32(wp + (size_t)k * CO);
            const float32x4_t w0 = vdupq_laneq_f32(wv, 0), w1 = vdupq_laneq_f32(wv, 1),
                               w2 = vdupq_laneq_f32(wv, 2), w3 = vdupq_laneq_f32(wv, 3);
            a0[0]=vfmaq_f32(a0[0],w0,x0); a0[1]=vfmaq_f32(a0[1],w0,x1);
            a0[2]=vfmaq_f32(a0[2],w0,x2); a0[3]=vfmaq_f32(a0[3],w0,x3);
            a1[0]=vfmaq_f32(a1[0],w1,x0); a1[1]=vfmaq_f32(a1[1],w1,x1);
            a1[2]=vfmaq_f32(a1[2],w1,x2); a1[3]=vfmaq_f32(a1[3],w1,x3);
            a2[0]=vfmaq_f32(a2[0],w2,x0); a2[1]=vfmaq_f32(a2[1],w2,x1);
            a2[2]=vfmaq_f32(a2[2],w2,x2); a2[3]=vfmaq_f32(a2[3],w2,x3);
            a3[0]=vfmaq_f32(a3[0],w3,x0); a3[1]=vfmaq_f32(a3[1],w3,x1);
            a3[2]=vfmaq_f32(a3[2],w3,x2); a3[3]=vfmaq_f32(a3[3],w3,x3);
          }
          for (int s = 0; s < 4; s++) {
            const float32x4_t* aa = s == 0 ? a0 : (s == 1 ? a1 : (s == 2 ? a2 : a3));
            float* yr = y + ((size_t)b * CO + m0 + s) * T + t0;
            for (int q = 0; q < 4; q++) {
              float32x4_t v = aa[q];
              if (ep == 2) v = vbslq_f32(vcltq_f32(v, vz), vmulq_f32(v, va), v);
              else if (ep == 1) v = vmaxq_f32(v, vz);
              vst1q_f32(yr + 4 * q, v);
            }
          }
        }
        const int tt = (T / 16) * 16;
        if (tt < T) {
          for (int s = 0; s < 4; s++) {
            float* yr = y + ((size_t)b * CO + m0 + s) * T;
            const float bv = s == 0 ? bias[m0] : (s == 1 ? bias[m0+1] : (s == 2 ? bias[m0+2] : bias[m0+3]));
            for (int t = tt; t < T; t++) {
              float acc = bv;
              for (int k = 0; k < cin; k++)
                acc += wp[(size_t)k * CO + s] * xb[(size_t)k * T + t];
              if (ep == 2) acc = leaky(acc, alpha);
              else if (ep == 1 && acc < 0.f) acc = 0.f;
              yr[t] = acc;
            }
          }
        }
        continue;
      }
#endif
      for (int t0 = 0; t0 < T; t0 += 32) {
        const int tc = std::min(32, T - t0);
        float a0[32], a1[32], a2[32], a3[32];
        const float v0 = bias[m0];
        const float v1 = cnt > 1 ? bias[m0+1] : 0.f;
        const float v2 = cnt > 2 ? bias[m0+2] : 0.f;
        const float v3 = cnt > 3 ? bias[m0+3] : 0.f;
        for (int j = 0; j < tc; j++) { a0[j]=v0; a1[j]=v1; a2[j]=v2; a3[j]=v3; }
        for (int k = 0; k < cin; k++) {
          const float* xk = xb + (size_t)k * T + t0;
          const float w0 = wp[(size_t)k * CO + 0];
          const float w1 = cnt > 1 ? wp[(size_t)k*CO + 1] : 0.f;
          const float w2 = cnt > 2 ? wp[(size_t)k*CO + 2] : 0.f;
          const float w3 = cnt > 3 ? wp[(size_t)k*CO + 3] : 0.f;
          for (int j = 0; j < tc; j++) {
            const float xv = xk[j];
            a0[j] += w0 * xv; a1[j] += w1 * xv; a2[j] += w2 * xv; a3[j] += w3 * xv;
          }
        }
        for (int s = 0; s < cnt; s++) {
          float* yr = y + ((size_t)b * CO + m0 + s) * T + t0;
          const float* a = s == 0 ? a0 : (s == 1 ? a1 : (s == 2 ? a2 : a3));
          for (int j = 0; j < tc; j++) {
            float v = a[j];
            if (ep == 2) v = leaky(v, alpha);
            else if (ep == 1 && v < 0.f) v = 0.f;
            yr[j] = v;
          }
        }
      }
    }
  }
}

void Vinet::k_stem(const Op& o, int tid, int tn) {
  const int S = o.u0, stride = o.u1;
  const int B = o.d0, N = o.d3, K = 64;
  const int CO = (int)(wsize[o.w] / K);
  const int T = (S + N - K) / stride + 1;
  const float* audio = slot[o.in0];
  float* st = slot[o.state];
  float* y = slot[o.out];
  const float* W = wptr[o.w];
  const float* bias = wptr[o.bias];
  int u0, u1; split_range(B * T, tid, tn, &u0, &u1);
  std::vector<float> acc(CO);
  for (int u = u0; u < u1; u++) {
    const int b = u / T, i = u % T;
    for (int c = 0; c < CO; c++) acc[c] = bias[c];
    const float* aud = audio + (size_t)b * N;
    const float* stb = st + (size_t)b * S;
    for (int j = 0; j < K; j++) {
      int idx = stride * i + j;
      float xv;
      if (idx < S) xv = stb[idx];
      else xv = aud[idx - S];
      const float* wr = W + (size_t)j * CO;
      for (int c = 0; c < CO; c++) acc[c] += xv * wr[c];
    }
    for (int c = 0; c < CO; c++) {
      float v = acc[c];
      y[((size_t)b * CO + c) * T + i] = v < 0.f ? 0.f : v;
    }
  }
}

void Vinet::k_stem_state(const Op& o) {
  const int S = o.u0;
  const int B = o.d0, N = o.d3;
  const float* audio = slot[o.in0];
  float* st = slot[o.state];
  for (int b = 0; b < B; b++)
    memcpy(st + (size_t)b * S, audio + (size_t)b * N + N - S, S * 4);
}

void Vinet::k_dwstate(const Op& o, int tid, int tn) {
  const int T = o.d3, d = o.u0;
  const int rows = (o.d0 * o.d1 * o.d2);
  const int C = rows / 2;                       // 448
  const int S = (int)(slot_size[o.state] / rows);
  const int ep = o.u1; const float alpha = o.f0;
  const float* h = slot[o.in0];
  float* st = slot[o.state];
  float* y = slot[o.out];
  const float* W = wptr[o.w];
  const float* bias = wptr[o.bias];
  int r0, r1; split_range(rows, tid, tn, &r0, &r1);
  std::vector<float> tmp(S + T);
  for (int r = r0; r < r1; r++) {
    const int c = r % C;
    float* strow = st + (size_t)r * S;
    const float* hrow = h + (size_t)r * T;
    memcpy(tmp.data(), strow, S * 4);
    memcpy(tmp.data() + S, hrow, T * 4);
    const float w0 = W[c*3+0], w1 = W[c*3+1], w2 = W[c*3+2];
    const float bb = bias[c];
    float* orow = y + (size_t)r * T;
    for (int t = 0; t < T; t++) {
      float v = bb + w0 * tmp[t] + w1 * tmp[t + d] + w2 * tmp[t + 2*d];
      if (ep == 2) v = leaky(v, alpha);
      else if (ep == 1 && v < 0.f) v = 0.f;
      orow[t] = v;
    }
    if (S >= T) {
      memmove(strow, strow + T, (size_t)(S - T) * 4);
      memcpy(strow + S - T, hrow, T * 4);
    } else {
      memcpy(strow, hrow + (T - S), (size_t)S * 4);
    }
  }
}

void Vinet::k_norm1(const Op& o, int tid, int tn) {
  const int outer = o.d0 * o.d1, C = o.d2, T = o.d3;
  const float* x = slot[o.in0];
  int c0, c1; split_range(C, tid, tn, &c0, &c1);
  float* part = stat_scratch.data() + (size_t)tid * 2 * 256;
  for (int oi = 0; oi < outer && oi < 2; oi++) {
    float* p0 = part + oi * 256;
    float* p1 = p0 + 128;
    for (int t = 0; t < T; t++) { p0[t] = 0.f; p1[t] = 0.f; }
    for (int c = c0; c < c1; c++) {
      const float* xr = x + ((size_t)oi * C + c) * T;
      for (int t = 0; t < T; t++) { p0[t] += xr[t]; p1[t] += xr[t] * xr[t]; }
    }
  }
}

void Vinet::k_norm2(const Op& o, int tid, int tn) {
  const int outer = o.d0 * o.d1, C = o.d2, T = o.d3;
  const float eps = o.f0;
  const float* g = wptr[o.gamma];
  const float* bt = wptr[o.beta];
  const float* x = slot[o.in0];
  float* y = slot[o.out];
  const int nt = tn;
  int c0, c1; split_range(C, tid, tn, &c0, &c1);
  for (int oi = 0; oi < outer && oi < 2; oi++) {
    float mean[256], scale[256];
    for (int t = 0; t < T; t++) {
      float s = 0.f, s2 = 0.f;
      for (int r = 0; r < nt; r++) {
        const float* part = stat_scratch.data() + (size_t)r * 2 * 256 + oi * 256;
        s += part[t]; s2 += part[128 + t];
      }
      const float m = s / C;
      const float var = s2 / C - m * m;
      mean[t] = m;
      scale[t] = 1.f / sqrtf(var + eps);
    }
    for (int c = c0; c < c1; c++) {
      const float* xr = x + ((size_t)oi * C + c) * T;
      float* yr = y + ((size_t)oi * C + c) * T;
      const float gc = g[c], bc = bt[c];
      for (int t = 0; t < T; t++)
        yr[t] = (xr[t] - mean[t]) * scale[t] * gc + bc;
    }
  }
}

void Vinet::k_addstate(const Op& o, int tid, int tn) {
  const int T = o.d3;
  const int rows = o.d0 * o.d1 * o.d2;
  const int S = o.u0;
  const float* x = slot[o.in0];
  const float* h = slot[o.in1];
  float* st = slot[o.state];
  float* y = slot[o.out];
  int r0, r1; split_range(rows, tid, tn, &r0, &r1);
  for (int r = r0; r < r1; r++) {
    const float* xr = x + (size_t)r * T;
    const float* hr = h + (size_t)r * T;
    float* sr = st + (size_t)r * S;
    float* yr = y + (size_t)r * T;
    for (int j = 0; j < T; j++) {
      const float dl = (j < S) ? sr[j] : xr[j - S];
      yr[j] = hr[j] + dl;
    }
    for (int j = 0; j < S; j++)
      sr[j] = (T + j < S) ? sr[T + j] : xr[T + j - S];
  }
}

void Vinet::k_add(const Op& o, int tid, int tn) {
  const size_t n = (size_t)slot_size[o.out];
  const float* a = slot[o.in0];
  const float* b = slot[o.in1];
  float* y = slot[o.out];
  size_t i0, i1; { 
    int r0, r1; split_range((int)n, tid, tn, &r0, &r1);
    i0 = r0; i1 = r1;
  }
  for (size_t i = i0; i < i1; i++) y[i] = a[i] + b[i];
}

void Vinet::k_maskconv(const Op& o, int tid, int tn) {
  const int B = o.d0, Hin = o.d2, T = o.d3;
  const int KH = o.u0, pad = o.u1;
  const int Hout = Hin + 2 * pad - KH + 1;
  const float* x = slot[o.in0];
  float* y = slot[o.out];
  const float* W = wptr[o.w];
  const float bias = wptr[o.bias][0];
  int r0, r1; split_range(B * Hout, tid, tn, &r0, &r1);
  for (int r = r0; r < r1; r++) {
    const int b = r / Hout, hp = r % Hout;
    int klo = std::max(0, pad - hp);
    int khi = std::min(KH, Hin + pad - hp);
    float acc[128];
    for (int t = 0; t < T; t++) acc[t] = bias;
    for (int k = klo; k < khi; k++) {
      const float wv = W[k];
      const float* xr = x + ((size_t)b * Hin + (hp + k - pad)) * T;
      for (int t = 0; t < T; t++) acc[t] += wv * xr[t];
    }
    float* yr = y + (size_t)r * T;
    memcpy(yr, acc, T * 4);
  }
}

void Vinet::k_sigmoid(const Op& o, int tid, int tn) {
  const size_t n = (size_t)slot_size[o.out];
  float* x = slot[o.in0];
  float* y = slot[o.out];
  int r0, r1; split_range((int)n, tid, tn, &r0, &r1);
  for (int i = r0; i < r1; i++) y[i] = 1.f / (1.f + expf(-x[i]));
}

void Vinet::k_maskmul(const Op& o, int tid, int tn) {
  const int T = o.d3;
  const int rows = o.d0 * o.d1 * o.d2;
  const int S = o.u0;
  const float* sig = slot[o.in0];
  const float* ft = slot[o.in1];
  float* st = slot[o.state];
  float* y = slot[o.out];
  int r0, r1; split_range(rows, tid, tn, &r0, &r1);
  for (int r = r0; r < r1; r++) {
    const float* sg = sig + (size_t)r * T;
    const float* fr = ft + (size_t)r * T;
    float* sr = st + (size_t)r * S;
    float* yr = y + (size_t)r * T;
    for (int j = 0; j < T; j++) {
      const float dl = (j < S) ? sr[j] : fr[j - S];
      yr[j] = sg[j] * dl;
    }
    for (int j = 0; j < S; j++)
      sr[j] = (T + j < S) ? sr[T + j] : fr[T + j - S];
  }
}

void Vinet::k_deconv(const Op& o, int tid, int tn) {
  const int B = o.d0, C = o.d1, T = o.d3;
  const int stride = o.u0, pad = o.u1;
  const int K = (int)(wsize[o.w] / C);          // 64
  const float* x = slot[o.in0];
  float* st = slot[o.state];
  float* y = slot[o.out];
  const float* W = wptr[o.w];
  const float bias = wptr[o.bias][0];
  const int F = T + 1;                           // frames incl. state
  const int ON = (F - 1) * stride - 2 * pad + K; // output length per batch
  std::vector<float> accv(K);
  for (int b = tid; b < B; b += tn) {
    float* ob = y + (size_t)b * ON;
    for (int i = 0; i < ON; i++) ob[i] = bias;
    for (int f = 0; f < F; f++) {
      for (int k = 0; k < K; k++) accv[k] = 0.f;
      if (f == 0) {
        for (int c = 0; c < C; c++) {
          const float v = st[(size_t)b * C + c];
          const float* wr = W + (size_t)c * K;
          for (int k = 0; k < K; k++) accv[k] += v * wr[k];
        }
      } else {
        for (int c = 0; c < C; c++) {
          const float v = x[((size_t)b * C + c) * T + (f - 1)];
          const float* wr = W + (size_t)c * K;
          for (int k = 0; k < K; k++) accv[k] += v * wr[k];
        }
      }
      const int base = stride * f - pad;
      for (int k = 0; k < K; k++) {
        const int og = base + k;
        if (og >= 0 && og < ON) ob[og] += accv[k];
      }
    }
    for (int c = 0; c < C; c++)
      st[(size_t)b * C + c] = x[((size_t)b * C + c) * T + (T - 1)];
  }
}

void Vinet::run_block(const float* in, float* out) {
  memcpy(slot[audio_slot], in, (size_t)slot_size[audio_slot] * 4);
  for (const Op& o : ops) {
    switch (o.type) {
      case 1: pool->run([&](int t, int n) { k_stem(o, t, n); });
              pool->run([&](int t, int n) { if (t == 0) k_stem_state(o); }); break;
      case 2: pool->run([&](int t, int n) { k_gemm(o, t, n); }); break;
      case 3: pool->run([&](int t, int n) { k_dwstate(o, t, n); }); break;
      case 4: pool->run([&](int t, int n) { k_norm1(o, t, n); });
              pool->run([&](int t, int n) { k_norm2(o, t, n); }); break;
      case 5: pool->run([&](int t, int n) { k_addstate(o, t, n); }); break;
      case 6: pool->run([&](int t, int n) { k_add(o, t, n); }); break;
      case 7: pool->run([&](int t, int n) { k_maskconv(o, t, n); }); break;
      case 8: pool->run([&](int t, int n) { k_sigmoid(o, t, n); }); break;
      case 9: pool->run([&](int t, int n) { k_maskmul(o, t, n); }); break;
      case 10: pool->run([&](int t, int n) { k_deconv(o, t, n); }); break;
      default: fprintf(stderr, "unknown op %u\n", o.type); abort();
    }
  }
  memcpy(out, slot[out_slot], (size_t)slot_size[out_slot] * 4);
}

struct Args {
  std::string bin, inpath, outpath, statepath;
  int threads = 4;
  int iters = 30, warmup = 5, blocks = 8;
  bool bench = false, run = false, dump_states = false, reset = false, debug = false;
  std::vector<int> affinity;
};

}  // namespace

#ifndef VINET_NO_MAIN
int main(int argc, char** argv) {
  Args a;
  if (argc < 2) {
    fprintf(stderr, "usage: %s vinet.bin (--bench|--run in.raw out.raw) [--threads N] [--affinity 2-7] [--iters N] [--warmup N] [--states s.bin]\n", argv[0]);
    return 1;
  }
  a.bin = argv[1];
  for (int i = 2; i < argc; i++) {
    std::string s = argv[i];
    auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : std::string(); };
    if (s == "--bench") a.bench = true;
    else if (s == "--run") { a.run = true; a.inpath = next(); a.outpath = next(); }
    else if (s == "--threads") a.threads = atoi(next().c_str());
    else if (s == "--iters") a.iters = atoi(next().c_str());
    else if (s == "--warmup") a.warmup = atoi(next().c_str());
    else if (s == "--blocks") a.blocks = atoi(next().c_str());
    else if (s == "--debug") a.debug = true;
    else if (s == "--reset") a.reset = true;
    else if (s == "--states") { a.dump_states = true; a.statepath = next(); }
    else if (s == "--affinity") {
      std::string v = next();
      size_t pos = 0;
      while (pos < v.size()) {
        int x = atoi(v.c_str() + pos);
        size_t dash = v.find('-', pos);
        size_t comma = v.find(',', pos);
        if (dash != std::string::npos && (comma == std::string::npos || dash < comma)) {
          int y = atoi(v.c_str() + dash + 1);
          for (int c = x; c <= y; c++) a.affinity.push_back(c);
          pos = dash + 1;
        } else {
          a.affinity.push_back(x);
        }
        size_t nxt = v.find(',', pos);
        if (nxt == std::string::npos) break;
        pos = nxt + 1;
      }
    }
  }

  Vinet v;
  if (!v.load(a.bin.c_str())) return 1;
  v.init_threads(std::max(0, a.threads - 1), a.affinity);

  if (a.run) {
    FILE* fi = fopen(a.inpath.c_str(), "rb");
    if (!fi) { fprintf(stderr, "cannot open %s\n", a.inpath.c_str()); return 1; }
    fseek(fi, 0, SEEK_END); long sz = ftell(fi); fseek(fi, 0, SEEK_SET);
    int nb = (int)(sz / (8192 * 4));
    if (a.blocks > 0) nb = std::min(nb, a.blocks);
    std::vector<float> in(8192), out(8192);
    FILE* fo = fopen(a.outpath.c_str(), "wb");
    for (int b = 0; b < nb; b++) {
      if (fread(in.data(), 4, 8192, fi) != 8192) break;
      uint64_t t0 = now_ns();
      v.run_block(in.data(), out.data());
      fprintf(stderr, "block %d: %.2f ms\n", b, (now_ns() - t0) / 1e6);
      fwrite(out.data(), 4, 8192, fo);
    }
    fclose(fi); fclose(fo);
    if (a.debug) {
      for (size_t k = 0; k < v.ops.size(); k++) {
        const Op& o = v.ops[k];
        float m = 0.f;
        uint32_t osize = v.slot_size[o.out];
        for (uint32_t i = 0; i < osize; i++) m = std::max(m, std::fabs(v.slot[o.out][i]));
        fprintf(stderr, "op %3zu type %2u  out slot %3u  size %6u  max|out| %.6e\n",
                k, o.type, o.out, osize, m);
      }
      for (size_t i = 0; i < v.slot_is_state.size(); i++)
        if (v.slot_is_state[i]) {
          float m = 0.f;
          for (uint32_t j = 0; j < v.slot_size[i]; j++) m = std::max(m, std::fabs(v.slot[i][j]));
          fprintf(stderr, "state slot %3zu size %6u max %.6e\n", i, v.slot_size[i], m);
        }
    }
    if (a.dump_states) {
      FILE* fs = fopen(a.statepath.c_str(), "wb");
      uint32_t n = 0;
      for (size_t i = 0; i < v.slot_is_state.size(); i++) if (v.slot_is_state[i]) n++;
      fwrite(&n, 4, 1, fs);
      for (size_t i = 0; i < v.slot_is_state.size(); i++) {
        if (!v.slot_is_state[i]) continue;
        uint32_t sl = (uint32_t)i, cnt = v.slot_size[i];
        fwrite(&sl, 4, 1, fs); fwrite(&cnt, 4, 1, fs);
        fwrite(v.slot[i], 4, cnt, fs);
      }
      fclose(fs);
      fprintf(stderr, "states dumped: %u\n", n);
    }
    return 0;
  }

  if (a.bench) {
    std::vector<float> in(8192), out(8192);
    for (size_t i = 0; i < in.size(); i++)
      in[i] = sinf(i * 0.017f) * 0.2f + 0.05f * sinf(i * 0.211f);
    v.reset_states();
    for (int i = 0; i < a.warmup; i++) v.run_block(in.data(), out.data());
    std::vector<double> ts;
    for (int i = 0; i < a.iters; i++) {
      uint64_t t0 = now_ns();
      v.run_block(in.data(), out.data());
      ts.push_back((now_ns() - t0) / 1e6);
    }
    std::sort(ts.begin(), ts.end());
    double sum = 0; for (double t : ts) sum += t;
    fprintf(stderr, "threads=%d  avg %.2f ms  min %.2f  p50 %.2f  p90 %.2f  budget 92.88 ms/block\n",
            a.threads, sum / ts.size(), ts.front(), ts[ts.size()/2], ts[ts.size()*9/10]);
    return 0;
  }
  fprintf(stderr, "nothing to do\n");
  return 0;
}
#endif  // VINET_NO_MAIN
