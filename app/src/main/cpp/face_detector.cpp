#include "face_detector.h"
#include <android/asset_manager_jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager.h>
#include <android/log.h>
#include <ncnn/net.h>
#include <vector>
#include <array>
#include <unordered_map>
#include <algorithm>
#include <cmath>
#include <ncnn/cpu.h>
#include <mutex>
static std::mutex g_detect_mutex;
FaceDetector::FaceDetector() {
    ncnn::set_omp_num_threads(1);
    net.opt.num_threads = 1;// 调试时用 1，确认后可改为 2-4
    net.opt.lightmode = false;              // 调试阶段关闭 lightmode 以避免内存优化干扰
    net.opt.use_vulkan_compute = false;     // 强制 CPU 模式（排查 Vulkan/驱动/FP16 导致的 NaN）
    net.opt.use_fp16_packed = false;
    net.opt.use_fp16_storage = false;
    net.opt.use_packing_layout = false;
    __android_log_print(ANDROID_LOG_INFO, "FaceDetector", "FaceDetector constructed: omp_threads=1 net.opt.num_threads=%d", net.opt.num_threads);
}
// 调试用：尝试交换模型输出中的 w 和 h 字段（临时试验）
const bool SWAP_WH = true;

// 析构函数
FaceDetector::~FaceDetector() {
    net.clear();
}

int FaceDetector::loadModel(AAssetManager* asset_mgr) {
    if (net.load_param(asset_mgr, "model.param") != 0)
        return -1;
    if (net.load_model(asset_mgr, "model.bin") != 0)
        return -1;
    return 0;
}



int FaceDetector::detect(ncnn::Mat &in, std::vector<FaceInfo> &faceList,
                         float score_threshold, int orig_w, int orig_h) {
    std::lock_guard<std::mutex> lg(g_detect_mutex);
    faceList.clear();

    ncnn::Extractor ex = net.create_extractor();

    int ret_in = ex.input("images", in);
    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "ex.input returned: %d", ret_in);
    ncnn::Mat out;
    int ret_out = ex.extract("output0", out);
         if (ret_out != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceNative", "ex.extract failed - check output name");
        return 0;
    }

    if (out.c <= 0 || out.h <= 0 || out.w <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceNative", "output dims invalid c=%d h=%d w=%d", out.c, out.h, out.w);
        return 0;
    }
    size_t total_elem = (size_t)out.c * out.h * out.w;
    if (total_elem < 5) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceNative", "output total elements too small: %zu", total_elem);
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "Model output dims: c=%d, h=%d, w=%d", out.c, out.h, out.w);

    const int INPUT_SIZE = 640;
    float scale = (float)INPUT_SIZE / std::max(orig_w, orig_h);
    int resize_w = (int)(orig_w * scale);
    int resize_h = (int)(orig_h * scale);
    float pad_w = (INPUT_SIZE - resize_w) / 2.0f;
    float pad_h = (INPUT_SIZE - resize_h) / 2.0f;
    __android_log_print(ANDROID_LOG_INFO, "FaceNative",
                        "预处理参数: scale=%.6f, resize=(%d,%d), pad=(%.3f,%.3f)",
                        scale, resize_w, resize_h, pad_w, pad_h);

    // parse output into preds (N x 5) as before
    std::vector<std::array<float,5>> preds;
    if (out.c == 5 && out.h == 1) {

        for (int i = 0; i < 5; ++i) {
            if (out.channel(i).w < out.w) {
                __android_log_print(ANDROID_LOG_ERROR, "FaceNative",
                                    "channel %d width %d < required %d", i, out.channel(i).w, out.w);
                return 0;
            }
        }

        int N = out.w;
        preds.resize(N);
        for (int j = 0; j < N; ++j)
            for (int i=0;i<5;++i) preds[j][i] = out.channel(i).row(0)[j];
    } else if (out.c == 1 && out.h == 5) {

        if (out.h < 5) {
            __android_log_print(ANDROID_LOG_ERROR, "FaceNative",
                                "need 5 rows but only have %d", out.h);
            return 0;
        }
        for (int i = 0; i < 5; ++i) {
            if (out.channel(0).w < out.w) {
                __android_log_print(ANDROID_LOG_ERROR, "FaceNative",
                                    "row %d width %d < required %d", i, out.channel(0).w, out.w);
                return 0;
            }
        }

        int N = out.w;
        preds.resize(N);
        const float* r0 = out.channel(0).row(0);
        const float* r1 = out.channel(0).row(1);
        const float* r2 = out.channel(0).row(2);
        const float* r3 = out.channel(0).row(3);
        const float* r4 = out.channel(0).row(4);


        if (!r0 || !r1 || !r2 || !r3 || !r4) {
            __android_log_print(ANDROID_LOG_ERROR, "FaceNative",
                                "null pointer in row access: %p %p %p %p %p", r0, r1, r2, r3, r4);
            return 0;
        }

        for (int j=0;j<N;++j){ preds[j][0]=r0[j]; preds[j][1]=r1[j];
            preds[j][2]=r2[j]; preds[j][3]=r3[j]; preds[j][4]=r4[j];
        }
    } else if (out.h==1 && out.w==5 && out.c>1) {

        for (int j = 0; j < out.c; ++j) {
            if (out.channel(j).w < 5) {
                __android_log_print(ANDROID_LOG_ERROR, "FaceNative",
                                    "channel %d width %d < required 5", j, out.channel(j).w);
                return 0;
            }
        }

        int N = out.c;
        preds.resize(N);
        for (int j=0;j<N;++j) { const float* p = out.channel(j); for (int k=0;k<5;++k) preds[j][k] = p[k]; }
    } else {
        // fallback flatten
        std::vector<float> vec; vec.reserve(out.c*out.h*out.w);
        for (int q=0;q<out.c;++q){
            const float* p = out.channel(q);
            int sz = out.h * out.w; for (int i=0;i<sz;++i) vec.push_back(p[i]);
        }
        if (vec.size()%5!=0){
            __android_log_print(ANDROID_LOG_ERROR,"FaceNative","不能解析 output vec size=%zu", vec.size());
            return 0;
        }
        int N = (int)vec.size()/5;
        preds.resize(N);
        for (int j=0;j<N;++j) for (int k=0;k<5;++k) preds[j][k] = vec[j*5 + k];
    }

    // stats & raw sample
    int Npred = (int)preds.size();
    float max_coord = 0.f, max_conf=-1e9f, min_conf=1e9f; double sum_conf=0.0;
    for (int i=0;i<Npred;++i) {
        for (int k=0;k<4;++k) max_coord = std::max(max_coord, fabs(preds[i][k]));
        max_conf = std::max(max_conf, preds[i][4]);
        min_conf = std::min(min_conf, preds[i][4]);
        sum_conf += preds[i][4];
    }
    double mean_conf = sum_conf / std::max(1, Npred);
    bool normalized = (max_coord <= 1.01f);
    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "pred stats: N=%d max_coord=%.6f conf(max/min/mean)=%.6f/%.6f/%.6f normalized=%d",
                        Npred, max_coord, max_conf, min_conf, mean_conf, normalized?1:0);

   
    int show = std::min(20, Npred);
    for (int i=0;i<show;++i) {
        __android_log_print(ANDROID_LOG_INFO, "FaceNative",
                            "rawpred[%d] cx=%.6f cy=%.6f w=%.6f h=%.6f conf=%.6f",
                            i, preds[i][0], preds[i][1], preds[i][2], preds[i][3], preds[i][4]);
    }
// ---------- 更鲁棒的 stride 推断与自动解码选择 ----------
    int inferred_stride = 0;

    std::vector<int> uniq;
    uniq.reserve(Npred);
    for (int i = 0; i < Npred; ++i) uniq.push_back((int)round(preds[i][0]));
    std::sort(uniq.begin(), uniq.end());
    uniq.erase(std::unique(uniq.begin(), uniq.end()), uniq.end());
    std::vector<int> diffs;
    for (size_t i = 1; i < uniq.size(); ++i) {
        int d = uniq[i] - uniq[i-1];
        if (d > 0 && d < INPUT_SIZE) diffs.push_back(d);
    }

    std::unordered_map<int,int> cnt;
    for (int d : diffs) if (d > 1) cnt[d]++;
    int bestd = 0, bestc = 0;
    for (auto &kv : cnt) { if (kv.second > bestc) { bestc = kv.second; bestd = kv.first; } }
    if (bestd > 1) inferred_stride = bestd;
    else {
        // fallback: compute gcd of diffs >1
        int g = 0;
        for (int d : diffs) if (d > 1) {
                if (g == 0) g = d;
                else {
                    int a = g, b = d;
                    while (b != 0) {
                        int temp = b;
                        b = a % b;
                        a = temp; }
                    g = a;
                }
            }
        if (g > 1) inferred_stride = g;
    }

    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "initial inferred_stride = %d (unique cx count=%zu, diffs sample=%zu)", inferred_stride, uniq.size(), diffs.size());


    std::vector<int> stride_candidates = {4,8,16,32,64};
    if (inferred_stride > 1) {
        // put inferred at front if not already present
        if (std::find(stride_candidates.begin(), stride_candidates.end(), inferred_stride) == stride_candidates.end())
            stride_candidates.insert(stride_candidates.begin(), inferred_stride);
        else {
            // move inferred to front
            stride_candidates.erase(std::remove(stride_candidates.begin(), stride_candidates.end(), inferred_stride), stride_candidates.end());
            stride_candidates.insert(stride_candidates.begin(), inferred_stride);
        }
    }


    auto try_decode_count = [&](int stride, int mode, int w_opt)->int {
      
        const float MIN_SIZE = 24.0f;
        int good = 0;
        for (int i = 0; i < Npred; ++i) {
            float cx = preds[i][0], cy = preds[i][1], w = preds[i][2], h = preds[i][3], conf = preds[i][4];
            if (SWAP_WH) { float _tmp = w; w = h; h = _tmp; }

            if (conf < score_threshold) continue;
            float cx_px, cy_px, w_px, h_px;
            if (normalized) {
                cx_px = cx * INPUT_SIZE; cy_px = cy * INPUT_SIZE;
                w_px = w * INPUT_SIZE; h_px = h * INPUT_SIZE;
            } else {
                if (mode == 0) { cx_px = cx; cy_px = cy; w_px = w; h_px = h; }
                else if (mode == 1) { cx_px = cx * stride; cy_px = cy * stride;
                    w_px = (w_opt==0 ? w*stride : expf(w) * stride); h_px = (w_opt==0 ? h*stride : expf(h) * stride); }
                else { cx_px = (cx + 0.5f) * stride; cy_px = (cy + 0.5f) * stride;
                    w_px = (w_opt==0 ? w*stride : expf(w) * stride); h_px = (w_opt==0 ? h*stride : expf(h) * stride); }
            }
          
            float x1 = cx_px - w_px * 0.5f;
            float y1 = cy_px - h_px * 0.5f;
            float x2 = cx_px + w_px * 0.5f;
            float y2 = cy_px + h_px * 0.5f;
          
            x1 = (x1 - pad_w) / std::max(1e-6f, scale);
            y1 = (y1 - pad_h) / std::max(1e-6f, scale);
            x2 = (x2 - pad_w) / std::max(1e-6f, scale);
            y2 = (y2 - pad_h) / std::max(1e-6f, scale);
            float bw = x2 - x1, bh = y2 - y1;
            if (bw > MIN_SIZE && bh > MIN_SIZE) good++;
        }
        return good;
    };

    struct TryRec { int stride, mode, wopt, cnt; };
    std::vector<TryRec> tries;
    for (int stride : stride_candidates) {
        for (int mode = 0; mode < 3; ++mode) {
            for (int wopt = 0; wopt < 2; ++wopt) {
                int c = try_decode_count(stride, mode, wopt);
                tries.push_back({stride, mode, wopt, c});
                  }
        }
    }

    int best_stride = stride_candidates.front();
    int best_mode = 0;
    int best_wopt = 0;
    int best_cnt = -1;
    for (auto &t : tries) {
        if (t.cnt > best_cnt) { best_cnt = t.cnt; best_stride = t.stride; best_mode = t.mode; best_wopt = t.wopt; }
    }
    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "selected stride=%d mode=%d wopt=%d (count=%d)", best_stride, best_mode, best_wopt, best_cnt);

    if (best_cnt <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, "FaceNative", "所有解码组合尝试后均无有效候选（best_cnt=0）。请降低 score_threshold 或贴 raw preds 供进一步分析。");
        // still choose a fallback stride to avoid crash
        best_stride = (inferred_stride>1?inferred_stride:8);
        best_mode = 1; best_wopt = 0;
    }


    inferred_stride = best_stride;
    int selected_mode = best_mode;
    int selected_wopt = best_wopt;
    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "FINAL decode selection -> stride=%d mode=%d wopt=%d", inferred_stride, selected_mode, selected_wopt);

// ------------------- 用选中的 decode 模式做完整解码 -> candidates -------------------
    struct Candidate { float x1,y1,x2,y2,score,cx,cy; };
    std::vector<Candidate> candidates;
    const float MIN_SIZE_PX = 24.0f;
    const float MAX_AREA_RATIO = 0.5f;
    for (int i=0;i<Npred;++i) {
        float cx = preds[i][0], cy = preds[i][1], w = preds[i][2], h = preds[i][3], conf = preds[i][4];
        if (SWAP_WH) std::swap(w, h);

        if (conf < score_threshold) continue;
        float cx_px=0, cy_px=0, w_px=0, h_px=0;
        if (normalized) {
            cx_px = cx * INPUT_SIZE; cy_px = cy * INPUT_SIZE;
            w_px = w * INPUT_SIZE; h_px = h * INPUT_SIZE;
        } else if (selected_mode == 0) {
            cx_px = cx; cy_px = cy; w_px = w; h_px = h;
        } else if (selected_mode == 1) {
            cx_px = cx * inferred_stride; cy_px = cy * inferred_stride;
            w_px = (selected_wopt == 0 ? w * inferred_stride : expf(w) * inferred_stride);
            h_px = (selected_wopt == 0 ? h * inferred_stride : expf(h) * inferred_stride);
        } else {
            cx_px = (cx + 0.5f) * inferred_stride; cy_px = (cy + 0.5f) * inferred_stride;
            w_px = (selected_wopt == 0 ? w * inferred_stride : expf(w) * inferred_stride);
            h_px = (selected_wopt == 0 ? h * inferred_stride : expf(h) * inferred_stride);
        }
        float x1 = cx_px - w_px*0.5f;
        float y1 = cy_px - h_px*0.5f;
        float x2 = cx_px + w_px*0.5f;
        float y2 = cy_px + h_px*0.5f;
        // inverse letterbox -> original image coords
        x1 = (x1 - pad_w) / std::max(1e-6f, scale);
        y1 = (y1 - pad_h) / std::max(1e-6f, scale);
        x2 = (x2 - pad_w) / std::max(1e-6f, scale);
        y2 = (y2 - pad_h) / std::max(1e-6f, scale);
        // clip
        x1 = std::max(0.0f, std::min(x1, (float)(orig_w-1)));
        y1 = std::max(0.0f, std::min(y1, (float)(orig_h-1)));
        x2 = std::max(0.0f, std::min(x2, (float)(orig_w-1)));
        y2 = std::max(0.0f, std::min(y2, (float)(orig_h-1)));
        float bw = x2 - x1, bh = y2 - y1;
        if (bw <= MIN_SIZE_PX || bh <= MIN_SIZE_PX) continue;
        float area = bw * bh;
        if (area > orig_w * orig_h * MAX_AREA_RATIO) continue;
        Candidate c; c.x1=x1;c.y1=y1;c.x2=x2;c.y2=y2;c.score=conf;c.cx=(x1+x2)*0.5f;c.cy=(y1+y2)*0.5f;
        candidates.push_back(c);
    }

    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "TopK解码并基础过滤后候选数量: %zu", candidates.size());

    // ------------------- (可选) 计算 candidates 最近邻中位数供调参 -------------------
    if (!candidates.empty()) {
        int sampleN = std::min<int>(1000, candidates.size());
        std::vector<float> nn;
        for (int i=0;i<sampleN;++i) {
            float best = 1e9f;
            for (int j=0;j<sampleN;++j) {
                if (i==j) continue;
                float dx = candidates[i].cx - candidates[j].cx;
                float dy = candidates[i].cy - candidates[j].cy;
                float d = sqrtf(dx*dx + dy*dy);
                if (d < best) best = d;
            }
            if (best < 1e8f) nn.push_back(best);
        }
        if (!nn.empty()) {
            std::sort(nn.begin(), nn.end());
            float median = nn[nn.size()/2];
            __android_log_print(ANDROID_LOG_INFO, "FaceNative", "candidates NN median dist = %.3f (样本 %zu)", median, nn.size());
        }
    }

    // ------------------- 贪心合并 (center-based) + final NMS -------------------
    std::sort(candidates.begin(), candidates.end(), [](const Candidate &a, const Candidate &b){ return a.score > b.score; });
    const float CLUSTER_RADIUS = 60.0f; // 可根据 NN median 调整
    const float IOU_GROUP = 0.4f;
    const float FINAL_IOU = 0.45f;
    std::vector<char> used(candidates.size(), 0);
    std::vector<Candidate> merged;
    for (size_t i=0;i<candidates.size();++i) {
        if (used[i]) continue;
        float sx1 = candidates[i].x1 * candidates[i].score;
        float sy1 = candidates[i].y1 * candidates[i].score;
        float sx2 = candidates[i].x2 * candidates[i].score;
        float sy2 = candidates[i].y2 * candidates[i].score;
        float ssum = candidates[i].score;
        used[i] = 1;
        for (size_t j = i+1; j<candidates.size(); ++j) {
            if (used[j]) continue;
            float dx = candidates[i].cx - candidates[j].cx;
            float dy = candidates[i].cy - candidates[j].cy;
            float d2 = dx*dx + dy*dy;
            float xx1 = std::max(candidates[i].x1, candidates[j].x1);
            float yy1 = std::max(candidates[i].y1, candidates[j].y1);
            float xx2 = std::min(candidates[i].x2, candidates[j].x2);
            float yy2 = std::min(candidates[i].y2, candidates[j].y2);
            float w = std::max(0.0f, xx2 - xx1);
            float h = std::max(0.0f, yy2 - yy1);
            float inter = w*h;
            float area_i = (candidates[i].x2 - candidates[i].x1) * (candidates[i].y2 - candidates[i].y1);
            float area_j = (candidates[j].x2 - candidates[j].x1) * (candidates[j].y2 - candidates[j].y1);
            float iou = inter / (area_i + area_j - inter + 1e-6f);
            if (d2 <= CLUSTER_RADIUS*CLUSTER_RADIUS || iou >= IOU_GROUP) {
                sx1 += candidates[j].x1 * candidates[j].score;
                sy1 += candidates[j].y1 * candidates[j].score;
                sx2 += candidates[j].x2 * candidates[j].score;
                sy2 += candidates[j].y2 * candidates[j].score;
                ssum += candidates[j].score;
                used[j] = 1;
            }
        }
        Candidate out;
        out.x1 = sx1 / ssum; out.y1 = sy1/ssum; out.x2 = sx2/ssum; out.y2 = sy2/ssum;
        out.score = candidates[i].score;
        out.cx = (out.x1 + out.x2)*0.5f; out.cy = (out.y1 + out.y2)*0.5f;
        merged.push_back(out);
    }
    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "贪心合并后候选数量: %zu", merged.size());

    // final NMS
    std::sort(merged.begin(), merged.end(), [](const Candidate &a, const Candidate &b){ return a.score > b.score; });
    std::vector<char> rem(merged.size(), 0);
    std::vector<Candidate> final_boxes;
    for (size_t i=0;i<merged.size();++i) {
        if (rem[i]) continue;
        final_boxes.push_back(merged[i]);
        for (size_t j=i+1;j<merged.size();++j) {
            if (rem[j]) continue;
            float xx1 = std::max(merged[i].x1, merged[j].x1);
            float yy1 = std::max(merged[i].y1, merged[j].y1);
            float xx2 = std::min(merged[i].x2, merged[j].x2);
            float yy2 = std::min(merged[i].y2, merged[j].y2);
            float w = std::max(0.0f, xx2 - xx1);
            float h = std::max(0.0f, yy2 - yy1);
            float inter = w*h;
            float area_i = (merged[i].x2 - merged[i].x1)*(merged[i].y2 - merged[i].y1);
            float area_j = (merged[j].x2 - merged[j].x1)*(merged[j].y2 - merged[j].y1);
            float iou = inter / (area_i + area_j - inter + 1e-6f);
            if (iou > FINAL_IOU) rem[j] = 1;
        }
        if (final_boxes.size() >= 20) break; // keep top 20
    }

    for (const auto &b : final_boxes) {
        FaceInfo fi; fi.x=b.x1; fi.y=b.y1; fi.w=b.x2-b.x1; fi.h=b.y2-b.y1; fi.score=b.score;
        faceList.push_back(fi);
    }

    __android_log_print(ANDROID_LOG_INFO, "FaceNative", "最终检测结果: %zu 个人脸", faceList.size());
    return (int)faceList.size();
}

