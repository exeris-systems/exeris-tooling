/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.storage.blob;

/**
 * Test stub for kernel {@code eu.exeris.kernel.spi.storage.blob.BlobRef}.
 *
 * @param container container name
 * @param key       object key
 */
public record BlobRef(String container, String key) {
}
