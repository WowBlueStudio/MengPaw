// SPDX-FileCopyrightText: 2026 MengPaw
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.core.security

import org.junit.Assert.*
import org.junit.Test

class SanitizerTest {

    @Test
    fun `sanitize openai key`() {
        val input = "My key is sk-proj-abc123def456ghi789jkl012mnopqr"
        val result = Sanitizer.sanitize(input)
        assertTrue(result.contains("***REDACTED_sk-p***"))
        assertFalse(result.contains("sk-proj-abc123def456ghi789jkl012mnopqr"))
    }

    @Test
    fun `sanitize anthropic key`() {
        val input = "Key: sk-ant-api03-abc123def456ghi789jkl012mno345pqr678stu901vwx234yza"
        val result = Sanitizer.sanitize(input)
        assertTrue(result.contains("***REDACTED_sk-a***"))
    }

    @Test
    fun `sanitize bearer token`() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
        val result = Sanitizer.sanitize(input)
        assertTrue(result.contains("***REDACTED_"))
    }

    @Test
    fun `normal text unchanged`() {
        val input = "Hello, this is a normal message without any secrets"
        val result = Sanitizer.sanitize(input)
        assertEquals(input, result)
    }

    @Test
    fun `sanitize throwable`() {
        val exception = RuntimeException("sk-proj-abc123def456ghi789jkl012")
        val result = Sanitizer.sanitizeThrowable(exception)
        assertTrue(result.contains("***REDACTED_"))
    }
}

class SecurityPolicyTest {

    private val policy = SecurityPolicy()

    @Test
    fun `allowed commands pass`() {
        assertTrue(policy.isAllowed("fs.cat /path"))
        assertTrue(policy.isAllowed("self.status"))
        assertTrue(policy.isAllowed("fs.ls /data"))
    }

    @Test
    fun `blocked commands fail`() {
        assertFalse(policy.isAllowed("proc.exec"))
    }

    @Test
    fun `dangerous patterns blocked`() {
        assertFalse(policy.isAllowed("rm -rf /"))
        assertFalse(policy.isAllowed("rm /"))
    }

    @Test
    fun `mkfs pattern blocked`() {
        assertFalse(policy.isAllowed("mkfs.ext4 /dev/sda"))
    }

    @Test
    fun `dd pattern blocked`() {
        assertFalse(policy.isAllowed("dd if=/dev/zero of=/dev/sda"))
    }

    @Test
    fun `chmod 777 slash pattern blocked`() {
        assertFalse(policy.isAllowed("chmod 777 /"))
    }

    @Test
    fun `normal commands pass for any namespace`() {
        assertTrue(policy.isAllowed("ui.click 100 200"))
        assertTrue(policy.isAllowed("memory.ls"))
        assertTrue(policy.isAllowed("skill.run test"))
        assertTrue(policy.isAllowed("net.curl https://example.com"))
    }

    @Test
    fun `blockList command blocked`() {
        assertFalse(policy.isAllowed("proc.exec"))
        assertFalse(policy.isAllowed("proc.exec ls"))
    }
}
