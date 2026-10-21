package com.example

import com.example.ui.screens.runtime.buttonIdToKeyCode
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun testButtonIdToKeyCodeMapping() {
    assertEquals(android.view.KeyEvent.KEYCODE_DPAD_UP, buttonIdToKeyCode("dpad_up"))
    assertEquals(android.view.KeyEvent.KEYCODE_DPAD_DOWN, buttonIdToKeyCode("dpad_down"))
    assertEquals(android.view.KeyEvent.KEYCODE_DPAD_LEFT, buttonIdToKeyCode("dpad_left"))
    assertEquals(android.view.KeyEvent.KEYCODE_DPAD_RIGHT, buttonIdToKeyCode("dpad_right"))
    assertEquals(android.view.KeyEvent.KEYCODE_Z, buttonIdToKeyCode("btn_a"))
    assertEquals(android.view.KeyEvent.KEYCODE_X, buttonIdToKeyCode("btn_b"))
    assertEquals(android.view.KeyEvent.KEYCODE_C, buttonIdToKeyCode("btn_c"))
    assertEquals(android.view.KeyEvent.KEYCODE_A, buttonIdToKeyCode("btn_x"))
    assertEquals(android.view.KeyEvent.KEYCODE_S, buttonIdToKeyCode("btn_y"))
    assertEquals(android.view.KeyEvent.KEYCODE_D, buttonIdToKeyCode("btn_z"))
    assertEquals(android.view.KeyEvent.KEYCODE_Q, buttonIdToKeyCode("btn_d"))
    assertEquals(android.view.KeyEvent.KEYCODE_W, buttonIdToKeyCode("btn_w"))
    assertEquals(android.view.KeyEvent.KEYCODE_ENTER, buttonIdToKeyCode("btn_start"))
    assertEquals(android.view.KeyEvent.KEYCODE_ESCAPE, buttonIdToKeyCode("btn_menu"))
    assertEquals(0, buttonIdToKeyCode("unknown_btn"))
  }
}
