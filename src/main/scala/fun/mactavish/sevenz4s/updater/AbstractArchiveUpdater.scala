package fun.mactavish.sevenz4s.updater

import java.io.{Closeable, InputStream, OutputStream}
import java.nio.file.Path

import fun.mactavish.sevenz4s.{CompressionEntry, SevenZ4S, SevenZ4SException}
import net.sf.sevenzipjbinding._
import net.sf.sevenzipjbinding.impl.OutItemFactory

import scala.collection.mutable

/**
 * A skeleton for ArchiveUpdater, most methods and fields are implemented.
 * ArchiveUpdater must set up a destination for output archive stream
 * and a source where input archive stream comes from, and some configurations
 * are optional. Each update operation contains complete steps of opening archive,
 * processing and closing archive. Hooks are not supported by ArchiveUpdater
 * since update operations usually finish quickly and leave little meaning
 * to track.
 *
 * @tparam E subclass should pass it's own type here to enable chained method calls
 */
private[sevenz4s] trait AbstractArchiveUpdater[E <: AbstractArchiveUpdater[E]] {
  this: E =>

  protected type TEntry <: CompressionEntry
  protected type TItem <: IOutItemBase
  /**
   * Subclasses should determine the format of this archive.
   */
  protected val format: ArchiveFormat

  private var source: Either[Path, InputStream] = _
  private var dst: Either[Path, OutputStream] = _
  /**
   * If password is set, then the archive will be opened with given password.
   */
  private var password: String = _

  /**
   * Specifies where archive stream comes from.
   *
   * For `Path`, it reads the given file and closes it afterwards during each operation.
   * For `InputStream`, it reads all bytes from the input stream during each operation
   * which costs O(N) both on space and time. Therefore, it's usually more
   * efficient to pass `Path` when you intend to provide data from a file.
   *
   * Additionally, in the second case, remember to close `InputStream`
   * on your own afterwards.
   *
   * @param src where archive stream comes from
   * @return updater itself so that method calls can be chained
   */
  def from(src: Either[Path, InputStream]): E = {
    if (src == null) throw SevenZ4SException("`from` doesn't accept null parameter")
    this.source = src
    this.source match {
      case Left(path) =>
        // if `src` comes from file, then `dst` is set the same as `src` by default
        if (this.dst == null) this.dst = Left(path)
      case Right(_) =>
    }
    this
  }

  def towards(dst: Either[Path, OutputStream]): E = {
    if (dst == null) throw SevenZ4SException("`towards` doesn't accept null parameter")

    this.dst = dst
    this
  }

  /**
   * Provides password for encrypted archive.
   * Note that supplying no password for encrypted archive will result in
   * a silent failure.
   *
   * @param passwd password
   * @return updater itself so that method calls can be chained
   */
  def withPassword(passwd: String): E = {
    if (passwd == null) throw SevenZ4SException("null passwd is meaningless")
    this.password = passwd
    this
  }

  /**
   * Alias for `remove`.
   *
   * Removes given entry from the archive.
   *
   * Note that given entry must equals exactly to one entry in the archive.
   * It's recommended that the parameter is exactly pulled from
   * the archive through other methods.
   * Avoid constructing the entry by yourself.
   *
   * @param entry one entry to remove from the archive
   * @return updater itself so that method calls can be chained
   */
  def -=(entry: TEntry): E = remove(entry)

  /**
   * Removes given entry from the archive.
   *
   * Note that given entry must equals exactly to one entry in the archive.
   * It's recommended that the parameter is exactly pulled from
   * the archive through other methods.
   * Avoid constructing the entry by yourself.
   *
   * @param entry one entry to remove from the archive
   * @return updater itself so that method calls can be chained
   */
  def remove(entry: TEntry): E = removeWhere(_ == entry)

  /**
   * Removes entries met given assumption from the archive.
   *
   * Each entry is supplied for assumption, the `source`
   * property of supplied entries is null as it's meaningless here.
   *
   * @param pred assumption on each entry
   * @return updater itself so that method calls can be chained
   */
  def removeWhere(pred: TEntry => Boolean): E = {
    val entriesToRemove = mutable.Set[CompressionEntry]()
    // find all entries to be removed first, as we need to know the total number
    update {
      entry =>
        if (pred(entry)) entriesToRemove += entry
        // simple traversal, update nothing
        entry
    }

    withArchive {
      (itemNum, archive, dst) =>
        archive.updateItems(dst, itemNum - entriesToRemove.size, new DefaultIOutCreateCallback {
          private var removalCnt = 0

          override def getItemInformation(i: Int, outItemFactory: OutItemFactory[TItem]): TItem = {
            val item = outItemFactory.createOutItemAndCloneProperties(i + removalCnt)
            val entry = adaptItemToEntry(item)
            if (entriesToRemove contains entry)
              removalCnt += 1
            outItemFactory.createOutItem(i + removalCnt)
          }

          override def cryptoGetTextPassword(): String = password

          // just removal, nothing to supply
          override def getStream(i: Int): ISequentialInStream = null
        })
        this
    }
  }

  /**
   * Iterates every entry and applies given update function.
   *
   * Update function takes each entry as input and ought to
   * return one entry. If the returned entry contains non-null
   * source, the inner entry will change its data to what comes
   * from the given source. If the returned entry contains different
   * properties from the original one, the inner entry will change its
   * properties accordingly.
   *
   * Adding or deleting an entry which changes the number of entries is
   * not supported here.
   *
   * @param f update function
   * @return updater itself so that method calls can be chained
   */
  def update(f: TEntry => TEntry): E = withArchive {
    (itemNum, archive, dst) =>
      val contentMap = mutable.HashMap[Int, Either[Path, InputStream]]()
      val entryStreams = mutable.HashSet[Closeable]()

      archive.updateItems(dst, itemNum, new DefaultIOutCreateCallback {
        override def getItemInformation(
                                         i: Int,
                                         outItemFactory: OutItemFactory[TItem]
                                       ): TItem = {
          val originalItem = outItemFactory.createOutItemAndCloneProperties(i)
          val originalEntry = adaptItemToEntry(originalItem)
          val newEntry = f(originalEntry)
          if (newEntry == null) throw SevenZ4SException("mustn't return empty entry")
          val newItem = adaptEntryToItem(newEntry, originalItem)
          // properties changed
          // TODO: For now, if `newEntry.source` is set, it'll also be marked as
          // `properties changed` since we determine it through simple comparison.
          // Though it is a big deal, we may improve it later.
          if (newEntry != originalEntry) newItem.setUpdateIsNewProperties(true)
          // content changed
          if (newEntry.source != null) {
            newItem.setUpdateIsNewData(true)
            contentMap(i) = newEntry.source
          }
          newItem
        }

        /**
         * `getStream` is called after all(`numEntry` times) `getItemInformation` calls,
         * but note that, it could be called for times fewer than `numEntry`,
         * since it'll only be called on `i` whose corresponding item triggered `setUpdateIsNewData`,
         * meaning `i` can be discontinuous (and truly so in most of time).
         *
         * @param i index of entry
         * @return where 7Z engine gets data stream
         */
        override def getStream(i: Int): ISequentialInStream = {
          val stream = SevenZ4S.open(contentMap(i))
          entryStreams.add(stream)
          stream
        }

        override def cryptoGetTextPassword(): String = password
      })

      entryStreams.foreach(_.close()) // close user-provided streams
      this
  }

  /**
   * Alias for `append`.
   *
   * Appends given entry to the archive.
   *
   * @param entry entry to append
   * @return updater itself so that method calls can be chained
   */
  def +=(entry: TEntry): E = append(entry)

  /**
   * Appends given entry to the archive.
   *
   * @param entry entry to append
   * @return updater itself so that method calls can be chained
   */
  def append(entry: TEntry): E = append(Seq(entry))

  /**
   * Alias for `append`.
   *
   * Appends given entries to the archive.
   *
   * Note that some archive formats (bzip2, gzip) only supports compression
   * of single archive entry (thus they're usually used along with tar).
   * So `++=` with Seq[TEntry] is made protected, only those supporting
   * multi-archive override it to public.
   *
   * @param entries entries to append
   * @return updater itself so that method calls can be chained
   */
  protected def ++=(entries: Seq[TEntry]): E = append(entries)

  /**
   * Appends given entries to the archive.
   *
   * Note that some archive formats (bzip2, gzip) only supports compression
   * of single archive entry (thus they're usually used along with tar).
   * So `append` with Seq[TEntry] is made protected, only those supporting
   * multi-archive override it to public.
   *
   * @param entries entries to append
   * @return updater itself so that method calls can be chained
   */
  protected def append(entries: Seq[TEntry]): E = withArchive {
    (itemNum, archive, dst) =>
      val entryStreams = mutable.HashSet[Closeable]()
      val entries_ = entries.toIndexedSeq

      // `updateItems` takes the number of items in NEW archive as a parameter
      archive.updateItems(dst, itemNum + entries_.size, new DefaultIOutCreateCallback {
        override def getItemInformation(i: Int, outItemFactory: OutItemFactory[TItem]): TItem = {
          if (i < itemNum) outItemFactory.createOutItem(i)
          else {
            adaptEntryToItem(entries_(i - itemNum), outItemFactory.createOutItem())
          }
        }

        override def getStream(i: Int): ISequentialInStream = {
          if (i < itemNum) null // existed items remain intact
          else {
            if (entries_(i - itemNum).source == null) null
            else {
              val src = SevenZ4S.open(entries_(i - itemNum).source)
              entryStreams.add(src)
              src
            }
          }
        }

        override def cryptoGetTextPassword(): String = password
      })

      // withArchive can't help with user-provided streams, close it here
      entryStreams.foreach(_.close())
      this
  }

  protected def adaptItemToEntry(item: TItem): TEntry

  protected def adaptEntryToItem(entry: TEntry, template: TItem): TItem

  /**
   * `DefaultIOutCreateCallback` extends `IOutCreateCallback[TItem]`
   * and `ICryptoGetTextPassword`, and it implements progress related behavior
   * with simply doing nothing.
   */
  trait DefaultIOutCreateCallback extends IOutCreateCallback[TItem] with ICryptoGetTextPassword {
    override def setOperationResult(b: Boolean): Unit = {}

    override def setTotal(l: Long): Unit = {}

    override def setCompleted(l: Long): Unit = {}
  }

  /**
   * `withArchive` opens `IOutUpdateArchive` and supplies number of items, archive itself and
   * `IOutStream`. Most importantly, it closes resources afterwards so that they can be reused.
   */
  private def withArchive[R](f: (Int, IOutUpdateArchive[TItem], IOutStream) => R): R = {
    val fromStream: IInStream = SevenZ4S.open(this.source)
    val outStream: IOutStream with Closeable = SevenZ4S.open(this.dst)

    val from = SevenZip.openInArchive(format, fromStream)
    val to = from.getConnectedOutArchive
    val res = f(from.getNumberOfItems, to.asInstanceOf[IOutUpdateArchive[TItem]], outStream)

    outStream.close()
    // InArchive needs to be closed, connected OutArchive will close automatically
    from.close()
    fromStream.close()

    res
  }
}

