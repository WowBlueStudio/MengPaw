package com.mengpaw.core.security

import org.junit.Assert.*
import org.junit.Test

class SecurityPolicyEdgeTest {

    private val policy = SecurityPolicy()

    @Test
    fun `empty string is allowed`() {
        assertTrue(policy.isAllowed(""))
    }

    @Test
    fun `blank string is allowed`() {
        assertTrue(policy.isAllowed("   "))
    }

    @Test
    fun `blockList prefix match blocks sub-commands`() {
        assertFalse(policy.isAllowed("proc.exec_cleanup"))
        assertFalse(policy.isAllowed("proc.exec --verbose"))
    }

    @Test
    fun `command containing but not starting with blocked prefix is allowed`() {
        // "my.proc.exec" starts with "my.", not "proc.exec"
        assertTrue(policy.isAllowed("my.proc.exec"))
    }

    @Test
    fun `uppercase rm command bypasses case-sensitive regex`() {
        assertTrue(policy.isAllowed("RM -RF /"))
    }

    @Test
    fun `rm relative path is allowed`() {
        assertTrue(policy.isAllowed("rm -rf ./local"))
    }

    @Test
    fun `chmod 777 on non-root path is allowed`() {
        assertTrue(policy.isAllowed("chmod 777 ./app"))
    }

    @Test
    fun `dd writing to non-dev path is allowed`() {
        assertTrue(policy.isAllowed("dd if=/dev/zero of=/tmp/backup.img"))
    }

    @Test
    fun `mkfs prefix match blocks all format variants`() {
        assertFalse(policy.isAllowed("mkfs.ext4 /dev/sda1"))
        assertFalse(policy.isAllowed("mkfs.ntfs /dev/sdb"))
        assertFalse(policy.isAllowed("mkfs.ext3"))
    }

    @Test
    fun `unicode path in command passes`() {
        assertTrue(policy.isAllowed("fs.cat /tmp/中文文件"))
    }
}
