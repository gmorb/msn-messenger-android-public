/* WMA -> 16-bit PCM WAV for the MSN Messenger importer (FFmpeg asf + wmav1/wmav2 only). LGPL-2.1+ */
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>

static void le16(FILE *f, int v) { fputc(v & 255, f); fputc((v >> 8) & 255, f); }
static void le32(FILE *f, uint32_t v) { le16(f, v & 0xffff); le16(f, v >> 16); }

static void wav_header(FILE *f, int ch, int rate, uint32_t data) {
    fwrite("RIFF", 1, 4, f); le32(f, 36 + data); fwrite("WAVEfmt ", 1, 8, f);
    le32(f, 16); le16(f, 1); le16(f, ch); le32(f, rate); le32(f, rate * ch * 2); le16(f, ch * 2); le16(f, 16);
    fwrite("data", 1, 4, f); le32(f, data);
}

static int16_t clip16(float s) { long v = lrintf(s * 32768.0f); if (v > 32767) v = 32767; if (v < -32768) v = -32768; return (int16_t)v; }

static uint32_t write_frame(FILE *f, AVFrame *fr, int ch) {
    int n = fr->nb_samples, fmt = fr->format;
    for (int i = 0; i < n; i++) for (int c = 0; c < ch; c++) {
        int16_t v = 0;
        switch (fmt) {
        case AV_SAMPLE_FMT_FLTP: v = clip16(((float *)fr->extended_data[c])[i]); break;
        case AV_SAMPLE_FMT_FLT:  v = clip16(((float *)fr->extended_data[0])[i * ch + c]); break;
        case AV_SAMPLE_FMT_S16P: v = ((int16_t *)fr->extended_data[c])[i]; break;
        case AV_SAMPLE_FMT_S16:  v = ((int16_t *)fr->extended_data[0])[i * ch + c]; break;
        default: return 0;
        }
        le16(f, (uint16_t)v);
    }
    return (uint32_t)n * ch * 2;
}

/* returns 0 on success, negative on error */
int msn_wma_to_wav(const char *in, const char *out) {
    AVFormatContext *fc = NULL; AVCodecContext *cc = NULL; AVPacket *pk = NULL; AVFrame *fr = NULL; FILE *f = NULL;
    int ret = -1, si, ch, rate; uint32_t data = 0;
    if (avformat_open_input(&fc, in, NULL, NULL) < 0) return -2;
    if (avformat_find_stream_info(fc, NULL) < 0) { ret = -3; goto end; }
    si = av_find_best_stream(fc, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (si < 0) { ret = -4; goto end; }
    const AVCodec *dec = avcodec_find_decoder(fc->streams[si]->codecpar->codec_id);
    if (!dec) { ret = -5; goto end; }
    cc = avcodec_alloc_context3(dec);
    avcodec_parameters_to_context(cc, fc->streams[si]->codecpar);
    if (avcodec_open2(cc, dec, NULL) < 0) { ret = -6; goto end; }
    ch = cc->ch_layout.nb_channels; rate = cc->sample_rate;
    if (ch < 1 || rate < 1) { ret = -7; goto end; }
    f = fopen(out, "wb"); if (!f) { ret = -8; goto end; }
    wav_header(f, ch, rate, 0);
    pk = av_packet_alloc(); fr = av_frame_alloc();
    for (int flush = 0; flush < 2; ) {
        if (!flush) {
            int r = av_read_frame(fc, pk);
            if (r < 0) { flush = 1; avcodec_send_packet(cc, NULL); }
            else { if (pk->stream_index == si) avcodec_send_packet(cc, pk); av_packet_unref(pk); }
        }
        int r;
        while ((r = avcodec_receive_frame(cc, fr)) >= 0) { data += write_frame(f, fr, ch); av_frame_unref(fr); }
        if (flush && r == AVERROR_EOF) flush = 2;
        else if (flush && r == AVERROR(EAGAIN)) flush = 2;
    }
    fseek(f, 0, SEEK_SET); wav_header(f, ch, rate, data);
    ret = data > 0 ? 0 : -9;
end:
    if (f) fclose(f);
    av_frame_free(&fr); av_packet_free(&pk); avcodec_free_context(&cc); avformat_close_input(&fc);
    return ret;
}

#ifdef __ANDROID__
#include <jni.h>
JNIEXPORT jint JNICALL Java_com_msnmessenger_WmaDecoder_decodeToWav(JNIEnv *e, jclass k, jstring jin, jstring jout) {
    const char *in = (*e)->GetStringUTFChars(e, jin, NULL), *out = (*e)->GetStringUTFChars(e, jout, NULL);
    int r = msn_wma_to_wav(in, out);
    (*e)->ReleaseStringUTFChars(e, jin, in); (*e)->ReleaseStringUTFChars(e, jout, out);
    return r;
}
#endif

#ifdef WMADEC_TEST_MAIN
int main(int argc, char **argv) { if (argc < 3) return 2; int r = msn_wma_to_wav(argv[1], argv[2]); printf("result %d\n", r); return r ? 1 : 0; }
#endif
