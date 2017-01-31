package voli

import java.nio.file.{Path, Paths}

object config {
  def indexDir: Path = Paths.get("blocks")
  def dictionaryFile: Path = indexDir.resolve("dictionary.txt")
  def indexFile: Path = indexDir.resolve("index.txt")
  def memoryLimit: Int = 100000
}
