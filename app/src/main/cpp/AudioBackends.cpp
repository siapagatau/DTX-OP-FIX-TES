#include "AudioBackend.h"

#include <oboe/Oboe.h>
#include <aaudio/AAudio.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <thread>
#include <vector>

// ===========================================================================
// 1) OBOE  (perilaku sama persis dengan versi sebelumnya)
// ===========================================================================
namespace {

class OboeBackend : public AudioBackend, public oboe::AudioStreamCallback {
public:
    explicit OboeBackend(AudioRenderer *r) : r_(r) {}
    ~OboeBackend() override { close(); }

    int id() const override { return BACKEND_OBOE; }

    bool open() override {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::Float)
               ->setChannelCount(oboe::ChannelCount::Stereo)
               ->setSampleRate(48000)
               // Tanpa SRC, Oboe boleh membuka stream di rate hardware (44100/32000/...)
               // padahal semua PCM kita 48kHz -> terdengar lambat & rendah nadanya.
               ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
               ->setCallback(this);

        // Coba 2x (jeda 60 ms) sebelum turun ke Shared: jalur Exclusive dari sesi sebelumnya
        // kadang belum sempurna dilepas sistem saat app dibuka ulang dengan cepat.
        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            std::this_thread::sleep_for(std::chrono::milliseconds(60));
            result = builder.openStream(stream_);
        }
        if (result != oboe::Result::OK) {
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream_);
            if (result != oboe::Result::OK) { stream_.reset(); return false; }
        }
        if (stream_->requestStart() != oboe::Result::OK) {
            stream_->close();
            stream_.reset();
            return false;
        }
        return true;
    }

    void close() override {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *s, void *data, int32_t n) override {
        r_->render(static_cast<float *>(data), n, s->getChannelCount(), s->getSampleRate());
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result) override {
        r_->onBackendDisconnected();
    }

private:
    AudioRenderer *r_;
    std::shared_ptr<oboe::AudioStream> stream_;
};

// ===========================================================================
// 2) AAUDIO langsung (NDK, API 26+ = minSdk proyek ini)
// ===========================================================================
class AAudioBackend : public AudioBackend {
public:
    explicit AAudioBackend(AudioRenderer *r) : r_(r) {}
    ~AAudioBackend() override { close(); }

    int id() const override { return BACKEND_AAUDIO; }

    bool open() override {
        if (tryOpen(true)) return true;
        std::this_thread::sleep_for(std::chrono::milliseconds(60));
        if (tryOpen(true)) return true;
        return tryOpen(false);  // Shared
    }

    void close() override {
        if (stream_) {
            AAudioStream_requestStop(stream_);
            AAudioStream_close(stream_);  // menunggu callback selesai
            stream_ = nullptr;
        }
    }

private:
    bool tryOpen(bool exclusive) {
        AAudioStreamBuilder *b = nullptr;
        if (AAudio_createStreamBuilder(&b) != AAUDIO_OK || b == nullptr) return false;
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(b, exclusive ? AAUDIO_SHARING_MODE_EXCLUSIVE
                                                        : AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
        AAudioStreamBuilder_setChannelCount(b, 2);
        AAudioStreamBuilder_setSampleRate(b, 48000);
        AAudioStreamBuilder_setDataCallback(b, &AAudioBackend::dataCb, this);
        AAudioStreamBuilder_setErrorCallback(b, &AAudioBackend::errorCb, this);

        AAudioStream *s = nullptr;
        aaudio_result_t res = AAudioStreamBuilder_openStream(b, &s);
        AAudioStreamBuilder_delete(b);
        if (res != AAUDIO_OK || s == nullptr) return false;

        // Hanya float stereo yang bisa dirender mixer. Format lain -> anggap gagal.
        if (AAudioStream_getFormat(s) != AAUDIO_FORMAT_PCM_FLOAT) {
            AAudioStream_close(s);
            return false;
        }
        channels_ = std::max(1, AAudioStream_getChannelCount(s));
        rate_ = AAudioStream_getSampleRate(s) > 0 ? AAudioStream_getSampleRate(s) : 48000;

        // Buffer 2x burst: titik awal umum untuk latensi rendah tanpa underrun.
        int32_t burst = AAudioStream_getFramesPerBurst(s);
        if (burst > 0) AAudioStream_setBufferSizeInFrames(s, burst * 2);

        stream_ = s;  // harus terpasang sebelum start (callback bisa langsung jalan)
        if (AAudioStream_requestStart(s) != AAUDIO_OK) {
            AAudioStream_close(s);
            stream_ = nullptr;
            return false;
        }
        return true;
    }

    static aaudio_data_callback_result_t dataCb(AAudioStream *, void *user, void *audio, int32_t n) {
        auto *self = static_cast<AAudioBackend *>(user);
        self->r_->render(static_cast<float *>(audio), n, self->channels_, self->rate_);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void errorCb(AAudioStream *, void *user, aaudio_result_t) {
        // Dipanggil dari thread khusus AAudio; stream tidak boleh ditutup di sini ->
        // PadEngine membuka ulang lewat thread-nya sendiri.
        static_cast<AAudioBackend *>(user)->r_->onBackendDisconnected();
    }

    AudioRenderer *r_;
    AAudioStream *stream_ = nullptr;
    int channels_ = 2;
    int rate_ = 48000;
};

// ===========================================================================
// 3) OPENSL ES langsung (NDK) - paling tua & paling kompatibel
// ===========================================================================
class OpenSLBackend : public AudioBackend {
public:
    OpenSLBackend(AudioRenderer *r, int rateHint, int framesHint)
        : r_(r),
          rate_(rateHint >= 8000 && rateHint <= 192000 ? rateHint : 48000),
          frames_(framesHint >= 32 && framesHint <= 4096 ? framesHint : 256) {}
    ~OpenSLBackend() override { close(); }

    int id() const override { return BACKEND_OPENSL; }

    bool open() override {
        if (build()) return true;
        destroyAll();
        return false;
    }

    void close() override {
        running_.store(false);
        if (playItf_) (*playItf_)->SetPlayState(playItf_, SL_PLAYSTATE_STOPPED);
        destroyAll();
    }

private:
    static constexpr int kBufCount = 3;

    bool build() {
        floatTmp_.assign((size_t)frames_ * 2, 0.0f);
        for (auto &b : bufs_) b.assign((size_t)frames_ * 2, 0);

        if (slCreateEngine(&engineObj_, 0, nullptr, 0, nullptr, nullptr) != SL_RESULT_SUCCESS) return false;
        if ((*engineObj_)->Realize(engineObj_, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return false;
        SLEngineItf engine;
        if ((*engineObj_)->GetInterface(engineObj_, SL_IID_ENGINE, &engine) != SL_RESULT_SUCCESS) return false;
        if ((*engine)->CreateOutputMix(engine, &mixObj_, 0, nullptr, nullptr) != SL_RESULT_SUCCESS) return false;
        if ((*mixObj_)->Realize(mixObj_, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return false;

        SLDataLocator_AndroidSimpleBufferQueue locBq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, kBufCount};
        SLDataFormat_PCM fmt = {SL_DATAFORMAT_PCM, 2, (SLuint32)rate_ * 1000,
                                SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
                                SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
                                SL_BYTEORDER_LITTLEENDIAN};
        SLDataSource src = {&locBq, &fmt};
        SLDataLocator_OutputMix locOut = {SL_DATALOCATOR_OUTPUTMIX, mixObj_};
        SLDataSink sink = {&locOut, nullptr};

        const SLInterfaceID ids[2] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_ANDROIDCONFIGURATION};
        const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_FALSE};
        if ((*engine)->CreateAudioPlayer(engine, &playerObj_, &src, &sink, 2, ids, req) != SL_RESULT_SUCCESS)
            return false;

        // Minta jalur latensi rendah (opsional: kalau perangkat menolak, tetap lanjut).
        SLAndroidConfigurationItf cfg = nullptr;
        if ((*playerObj_)->GetInterface(playerObj_, SL_IID_ANDROIDCONFIGURATION, &cfg) == SL_RESULT_SUCCESS && cfg) {
            SLuint32 mode = SL_ANDROID_PERFORMANCE_LATENCY;
            (*cfg)->SetConfiguration(cfg, SL_ANDROID_KEY_PERFORMANCE_MODE, &mode, sizeof(mode));
        }

        if ((*playerObj_)->Realize(playerObj_, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS) return false;
        if ((*playerObj_)->GetInterface(playerObj_, SL_IID_PLAY, &playItf_) != SL_RESULT_SUCCESS) return false;
        if ((*playerObj_)->GetInterface(playerObj_, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &queueItf_) != SL_RESULT_SUCCESS)
            return false;
        if ((*queueItf_)->RegisterCallback(queueItf_, &OpenSLBackend::bqCallback, this) != SL_RESULT_SUCCESS)
            return false;

        running_.store(true);
        // Isi antrean dengan keheningan; callback berikutnya menggantikannya dengan audio asli.
        for (int i = 0; i < kBufCount; i++) {
            if ((*queueItf_)->Enqueue(queueItf_, bufs_[i].data(),
                                      (SLuint32)(bufs_[i].size() * sizeof(int16_t))) != SL_RESULT_SUCCESS)
                return false;
        }
        next_ = 0;
        return (*playItf_)->SetPlayState(playItf_, SL_PLAYSTATE_PLAYING) == SL_RESULT_SUCCESS;
    }

    void destroyAll() {
        running_.store(false);
        if (playerObj_) { (*playerObj_)->Destroy(playerObj_); playerObj_ = nullptr; }
        playItf_ = nullptr;
        queueItf_ = nullptr;
        if (mixObj_) { (*mixObj_)->Destroy(mixObj_); mixObj_ = nullptr; }
        if (engineObj_) { (*engineObj_)->Destroy(engineObj_); engineObj_ = nullptr; }
    }

    static void bqCallback(SLAndroidSimpleBufferQueueItf, void *ctx) {
        static_cast<OpenSLBackend *>(ctx)->onBufferDone();
    }

    void onBufferDone() {
        if (!running_.load()) return;
        r_->render(floatTmp_.data(), frames_, 2, rate_);
        std::vector<int16_t> &buf = bufs_[next_];
        const size_t n = (size_t)frames_ * 2;
        for (size_t i = 0; i < n; i++) {
            float v = std::max(-1.0f, std::min(1.0f, floatTmp_[i]));
            buf[i] = (int16_t)std::lrintf(v * 32767.0f);
        }
        (*queueItf_)->Enqueue(queueItf_, buf.data(), (SLuint32)(n * sizeof(int16_t)));
        next_ = (next_ + 1) % kBufCount;
    }

    AudioRenderer *r_;
    int rate_;
    int frames_;
    std::atomic<bool> running_{false};
    SLObjectItf engineObj_ = nullptr;
    SLObjectItf mixObj_ = nullptr;
    SLObjectItf playerObj_ = nullptr;
    SLPlayItf playItf_ = nullptr;
    SLAndroidSimpleBufferQueueItf queueItf_ = nullptr;
    std::vector<float> floatTmp_;
    std::vector<int16_t> bufs_[kBufCount];
    int next_ = 0;
};

}  // namespace

std::unique_ptr<AudioBackend> createNativeBackend(int id, AudioRenderer *renderer,
                                                  int rateHint, int framesHint) {
    switch (id) {
        case BACKEND_OBOE:   return std::make_unique<OboeBackend>(renderer);
        case BACKEND_AAUDIO: return std::make_unique<AAudioBackend>(renderer);
        case BACKEND_OPENSL: return std::make_unique<OpenSLBackend>(renderer, rateHint, framesHint);
        default:             return nullptr;
    }
}
