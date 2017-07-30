package voli

import org.scalatest.FlatSpec
import voli.index.IndexRadix

class Index$Test extends FlatSpec with TestIO {
  val index = new IndexRadix()
  val nbsp: Char = 0x00A0
  val sp: Char = 0x0020
  val cr: Char = 0x0020

  "Tokenizer" should "split on whitespace" in {
    val line = """<button type=\"button\" class=\"bttn_71_arrow;\" onclick=\"window.location.href=''fin_crm_pages.p_disp_sections?p_section=new''\">^^^1^^^https://svn.wgu.edu/repos/prod/Projects/Finance_Scripts/fa_snap_rollback.sql"""
    val expected = line.split("[\\p{Z}\\s]+")

    val tokens = index.tokenize(line)
    assert(tokens.size == expected.size)
  }

  it should "return empty list on empty string" in {
    assert(index.tokenize("").isEmpty)
    assert(index.tokenize("\\s").isEmpty)
    assert(index.tokenize("\\s\\s").isEmpty)
    assert(index.tokenize("" + nbsp + nbsp).isEmpty)
  }
}
