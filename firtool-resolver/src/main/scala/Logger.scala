// SPDX-License-Identifier: Apache-2.0

package firtoolresolver

/** Minimal Logger API
  *
  * Used by firtoolresolver for its own purposes, easy for downstream projects to shim
  */
trait Logger {
  def error(msg: String): Unit
  def warn(msg: String): Unit
  def info(msg: String): Unit
  def debug(msg: String): Unit
  def trace(msg: String): Unit
}
object Logger {
  /** Print warning level and above to stderr */
  def warn: Logger = new Logger {
    def error(msg: String): Unit = Console.err.println(msg)
    def warn(msg: String): Unit = Console.err.println(msg)
    def info(msg: String): Unit = ()
    def debug(msg: String): Unit = ()
    def trace(msg: String): Unit = ()
  }
  /** Print debug level and above to stderr */
  def debug: Logger = new Logger {
    def error(msg: String): Unit = Console.err.println(msg)
    def warn(msg: String): Unit = Console.err.println(msg)
    def info(msg: String): Unit = Console.err.println(msg)
    def debug(msg: String): Unit = Console.err.println(msg)
    def trace(msg: String): Unit = ()
  }
}
