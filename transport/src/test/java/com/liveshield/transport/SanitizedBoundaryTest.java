package com.liveshield.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;
import org.junit.Test;

/** Tests-first contract for the only value type allowed to cross into publication. */
public final class SanitizedBoundaryTest {
    @Test
    public void copiesProducerBytesAndEveryReturnedPayload() {
        byte[] producerBuffer = {0, 0, 0, 1, 0x65, 7};
        EncodedAccessUnit unit = unit(
                producerBuffer,
                123_456L,
                Set.of(EncodedAccessUnit.Flag.KEY_FRAME));

        producerBuffer[4] = 0;
        byte[] firstRead = unit.payload();
        firstRead[5] = 0;

        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 7}, unit.payload());
        assertEquals(6, unit.size());
    }

    @Test
    public void fixesH264VideoSanitizedAttestationPtsAndImmutableFlags() {
        Set<EncodedAccessUnit.Flag> producerFlags =
                new java.util.HashSet<>(Set.of(
                        EncodedAccessUnit.Flag.CODEC_CONFIGURATION,
                        EncodedAccessUnit.Flag.KEY_FRAME));
        EncodedAccessUnit unit = unit(new byte[]{1, 2, 3}, 987L, producerFlags);
        producerFlags.clear();

        assertEquals(EncodedAccessUnit.TrackType.VIDEO, unit.trackType());
        assertEquals(EncodedAccessUnit.Codec.H264, unit.codec());
        assertEquals(
                EncodedAccessUnit.PrivacyAttestation.SANITIZED,
                unit.privacyAttestation());
        assertEquals(987L, unit.presentationTimeUs());
        assertEquals(
                Set.of(
                        EncodedAccessUnit.Flag.CODEC_CONFIGURATION,
                        EncodedAccessUnit.Flag.KEY_FRAME),
                unit.flags());
        assertThrows(
                UnsupportedOperationException.class,
                () -> unit.flags().add(EncodedAccessUnit.Flag.END_OF_STREAM));
    }

    @Test
    public void rejectsMissingPayloadFlagsAndInvalidPresentationTime() {
        assertThrows(NullPointerException.class,
                () -> unit(null, 1L, Set.of()));
        assertThrows(NullPointerException.class,
                () -> unit(new byte[]{1}, 1L, null));
        assertThrows(NullPointerException.class,
                () -> unit(
                        new byte[]{1},
                        1L,
                        setContainingNullFlag()));
        assertThrows(IllegalArgumentException.class,
                () -> unit(new byte[]{1}, -1L, Set.of()));
    }

    @Test
    public void classIsFinalWithNoPublicConstructorOrForgeableByteFactory() {
        assertTrue(Modifier.isFinal(EncodedAccessUnit.class.getModifiers()));
        for (Constructor<?> constructor : EncodedAccessUnit.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
            assertFalse(Modifier.isProtected(constructor.getModifiers()));
        }
        for (Method method : EncodedAccessUnit.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())) {
                assertFalse("Public factory could forge SANITIZED attestation: " + method,
                        Arrays.asList(method.getParameterTypes()).contains(byte[].class));
            }
        }
    }

    @Test
    public void publicBoundaryExposesNoAudioGenericRawSurfaceOrBufferType() {
        assertArrayEquals(
                new EncodedAccessUnit.TrackType[]{EncodedAccessUnit.TrackType.VIDEO},
                EncodedAccessUnit.TrackType.values());
        assertArrayEquals(
                new EncodedAccessUnit.Codec[]{EncodedAccessUnit.Codec.H264},
                EncodedAccessUnit.Codec.values());

        for (Method method : EncodedAccessUnit.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) {
                continue;
            }
            assertAllowedType(method.getReturnType());
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertAllowedType(parameterType);
            }
            assertFalse(method.getName().toLowerCase(java.util.Locale.ROOT).contains("audio"));
        }
    }

    @Test
    public void flagsAccessorCannotEraseConfigurationAndFrameFlagType() throws Exception {
        Method flags = EncodedAccessUnit.class.getDeclaredMethod("flags");
        assertTrue(flags.getGenericReturnType() instanceof ParameterizedType);
        ParameterizedType typedFlags = (ParameterizedType) flags.getGenericReturnType();

        assertEquals(Set.class, typedFlags.getRawType());
        assertArrayEquals(
                new Type[]{EncodedAccessUnit.Flag.class},
                typedFlags.getActualTypeArguments());
    }

    @Test
    public void instanceStateCannotBeReassignedAfterConstruction() {
        for (Field field : EncodedAccessUnit.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                assertTrue("Mutable field: " + field,
                        Modifier.isPrivate(field.getModifiers())
                                && Modifier.isFinal(field.getModifiers()));
            }
        }
    }

    private static EncodedAccessUnit unit(
            byte[] payload,
            long presentationTimeUs,
            Set<EncodedAccessUnit.Flag> flags) {
        return EncodedAccessUnit.copySanitizedH264(payload, presentationTimeUs, flags);
    }

    private static Set<EncodedAccessUnit.Flag> setContainingNullFlag() {
        Set<EncodedAccessUnit.Flag> flags =
                new java.util.HashSet<EncodedAccessUnit.Flag>();
        flags.add(null);
        return flags;
    }

    private static void assertAllowedType(Class<?> type) {
        String name = type.getName();
        assertFalse("Raw image type crossed transport API: " + name,
                name.contains("Image") || name.contains("Bitmap"));
        assertFalse("Surface crossed transport API: " + name, name.contains("Surface"));
        assertFalse("ByteBuffer crossed transport API: " + name,
                type == ByteBuffer.class || ByteBuffer.class.isAssignableFrom(type));
        assertFalse("Generic payload type crossed transport API: " + name,
                type == Object.class);
        assertFalse("Audio type crossed transport API: " + name,
                name.toLowerCase(java.util.Locale.ROOT).contains("audio"));
    }
}
