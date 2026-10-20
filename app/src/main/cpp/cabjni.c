/*
 * cabjni.c: extracts one file from the cabinet(s) embedded in the user's own
 * MSN Messenger 8 installer (.exe self-extractor, or a plain .cab), e.g. MsnMsgs.msi.
 * Uses libmspack 0.11alpha (LGPL-2.1, mspack/COPYING.LIB): cabd + lzxd/mszipd/qtmd.
 */
#include <stdio.h>
#include <string.h>
#include <strings.h>
#include "mspack.h"

/* 0 ok, -1 decompressor unavailable, -2 no cabinet found, -3 file not in cabinet, <-10 libmspack error */
int msn_cab_extract(const char *in, const char *name, const char *out) {
    struct mscab_decompressor *d = mspack_create_cab_decompressor(NULL);
    if (!d) return -1;
    struct mscabd_cabinet *cab = d->search(d, (char *) in);
    if (!cab) { mspack_destroy_cab_decompressor(d); return -2; }
    int r = -3;
    for (struct mscabd_cabinet *k = cab; k && r == -3; k = k->next) {
        for (struct mscabd_file *f = k->files; f; f = f->next) {
            if (f->filename && strcasecmp(f->filename, name) == 0) {
                int e = d->extract(d, f, (char *) out);
                r = e == MSPACK_ERR_OK ? 0 : -10 - e;
                break;
            }
        }
    }
    d->close(d, cab);
    mspack_destroy_cab_decompressor(d);
    if (r != 0) remove(out);
    return r;
}

#ifdef __ANDROID__
#include <jni.h>
JNIEXPORT jint JNICALL Java_com_msnmessenger_imp_NativeCab_extract(JNIEnv *e, jclass k,
        jstring jin, jstring jname, jstring jout) {
    const char *in = (*e)->GetStringUTFChars(e, jin, NULL);
    const char *name = (*e)->GetStringUTFChars(e, jname, NULL);
    const char *out = (*e)->GetStringUTFChars(e, jout, NULL);
    int r = msn_cab_extract(in, name, out);
    (*e)->ReleaseStringUTFChars(e, jin, in);
    (*e)->ReleaseStringUTFChars(e, jname, name);
    (*e)->ReleaseStringUTFChars(e, jout, out);
    return r;
}
#endif

#ifdef CABJNI_TEST_MAIN
int main(int argc, char **argv) {
    if (argc < 4) return 2;
    int r = msn_cab_extract(argv[1], argv[2], argv[3]);
    printf("result %d\n", r);
    return r ? 1 : 0;
}
#endif
