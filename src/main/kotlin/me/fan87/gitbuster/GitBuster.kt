@file:Suppress("NOTHING_TO_INLINE")

package me.fan87.gitbuster

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.GC
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.concurrent.withLock


@Suppress("MemberVisibilityCanBePrivate")
class GitBuster(
    val git: Git,
    val maxQueueSize: Int = 50,
    val threads: Int = 100,
) {

    private val repository = git.repository as FileRepository
    private val digest = MessageDigest.getInstance("SHA-1")
    private val written = AtomicInteger()
    private var opened = false
    private var closed = false

    private val writeQueue = LinkedList<Pair<String, ByteArray>>()
    private val queueLock = ReentrantLock()
    private val baseDir = repository.directory.parentFile
    private val gc = GC(repository)

    private var lastCommitId = getGitHead()

    private var lastWrittenObject = getGitHead()
    val lock = ReentrantLock()
    private val threadsList = ArrayList<Thread>()
    private val tree by lazy { ObjectId.fromString(getGitTree(git.repository.findRef("HEAD").objectId)) }


    private fun getGitHead(): String? {
        return git.repository.findRef("HEAD").objectId?.name
    }
    private fun getGitTree(objectId: ObjectId): String {
        val theObject = git.repository.objectDatabase.open(objectId)
        if (theObject.type != Constants.OBJ_COMMIT) {
            throw IllegalStateException("${objectId.name} is not a commit object")
        }
        val reader = theObject.openStream().bufferedReader()
        for (readLine in reader.lines()) {
            if (readLine.startsWith("tree ")) return readLine.replace("tree ", "")
            break
        }
        throw IllegalStateException("Could not find tree referred within the commit")
    }

    private fun startThreads(callback: (hash: String, amount: Int, writeLock: Lock) -> Unit) {
        repeat(threads) {
            threadsList.add(Thread {
                val deflater = Deflater(Deflater.NO_COMPRESSION)
                while (!closed) {
                    Thread.sleep(0, 1);
                    val (hash, data) = queueLock.withLock { writeQueue.removeFirstOrNull() } ?: continue
                    lock.withLock {
                        val fileStream = FileOutputStream(
                            File(
                                repository.objectsDirectory,
                                hash.substring(0, 2) + "/" + hash.substring(2)
                            ).also { it.parentFile.mkdirs() })
                        val stream = DeflaterOutputStream(
                            fileStream,
                            deflater
                        )
                        stream.write(data)
                        stream.close()
                        fileStream.close()
                        deflater.reset()
                        lastWrittenObject = hash
                    }
                    callback(hash, written.incrementAndGet(), lock)

                }
            }.also { it.start() })
        }
    }


    inline fun createCommit(builder: CommitBuilder.() -> Unit) = createCommit(CommitBuilder().apply(builder))

    @OptIn(ExperimentalStdlibApi::class)
    @Synchronized
    fun createCommit(commitBuilder: CommitBuilder, addParent: Boolean = true): String {
        if (!opened) throw IllegalStateException("GitBuster is not opened")

        if (commitBuilder.treeId == null) commitBuilder.setTreeId(tree)
        if (addParent && lastCommitId != null) commitBuilder.addParentId(ObjectId.fromString(lastCommitId))

        commitBuilder.encoding = Charsets.UTF_8
        val content = commitBuilder.toByteArray()
        val objectData = "commit ${content.size}\u0000".encodeToByteArray() + content

        val hex = digest.digest(objectData).toHexString(HexFormat.Default)

        lastCommitId = hex
        while (writeQueue.size > maxQueueSize) {
            Thread.sleep(0, 1);
        }

        queueLock.withLock { writeQueue.add(hex to objectData) }

        return hex
    }


    fun open(commitObjectCreateCallback: (hash: String, amount: Int, writeLock: Lock) -> Unit = { _, _, _ -> }) {
        if (opened) throw IllegalStateException("GitBuster is already opened")
        if (closed) throw IllegalStateException("GitBuster is already closed")
        opened = true
        updateRef()
        startThreads(commitObjectCreateCallback)
    }

    fun close() {
        if (!opened) return
        while (writeQueue.isNotEmpty()) {
            Thread.sleep(0, 1);
        }
        closed = true
        threadsList.forEach { it.join() }
        updateRef()
    }

    fun updateRef() {
        val updateRef = repository.updateRef("HEAD")
        updateRef.setNewObjectId(ObjectId.fromString(lastCommitId))
        updateRef.update()
    }

    fun repack() {
        gc.repack()
    }

    fun push(shrink: Boolean = true) {
        close()

        repack()
        git.push().call()

        if (shrink) shrink()
    }

    fun shrink() {
        close()

        val tempRepo = File(System.getProperty("java.io.tmpdir"), "/gitbuster/${UUID.randomUUID()}")

        Git.cloneRepository()
            .setDepth(1)
            .setMirror(true)
            .setURI(baseDir.toURI().toString())
            .setDirectory(tempRepo)
            .call()

        File(baseDir, ".git/objects").deleteRecursively()
        File(baseDir, ".git/refs").deleteRecursively()
        File(baseDir, ".git/info").deleteRecursively()
        File(baseDir, ".git/logs").deleteRecursively()
        File(baseDir, ".git/shallow").delete()
        File(baseDir, ".git/packed-refs").delete()

        File(tempRepo, "objects").takeIf { it.exists() }?.copyRecursively(File(baseDir, ".git/objects"), true)
        File(tempRepo, "refs").takeIf { it.exists() }?.copyRecursively(File(baseDir, ".git/refs"), true)
        File(tempRepo, "info").takeIf { it.exists() }?.copyRecursively(File(baseDir, ".git/info"), true)
        File(tempRepo, "shallow").takeIf { it.exists() }?.copyTo(File(baseDir, ".git/shallow"))
        File(tempRepo, "packed-refs").takeIf { it.exists() }?.copyTo(File(baseDir, ".git/packed-refs"))

        tempRepo.deleteRecursively()
    }
}