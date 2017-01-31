package voli

import java.nio.file.Paths

trait TestIO {
  def testDirURI = getClass.getClassLoader.getResource(".").toURI

  def testDirStringPath = testDirURI.getPath

  def testDirPath = Paths.get(testDirURI).toAbsolutePath

}
