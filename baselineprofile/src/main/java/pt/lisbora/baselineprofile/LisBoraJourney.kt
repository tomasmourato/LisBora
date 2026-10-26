package pt.lisbora.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "pt.lisbora"

/** Waits for the map screen: the search bar shows once the data pack is in place. */
fun MacrobenchmarkScope.waitForMap() {
    device.wait(Until.hasObject(By.text(SEARCH_HINT)), 30_000)
}

/** What people do first: look for a place (the search index loads) and open Descobrir (the graph loads). */
fun MacrobenchmarkScope.searchAndDiscover() {
    device.findObject(By.text(SEARCH_HINT))?.click() ?: return
    device.wait(Until.hasObject(By.focused(true)), 5_000)
    device.findObject(By.focused(true))?.text = "rossio"
    device.wait(Until.hasObject(By.textContains("Lisboa")), 15_000)
    device.pressBack()
    device.pressBack()
    device.wait(Until.hasObject(By.text("Descobrir")), 5_000)
    device.findObject(By.text("Descobrir"))?.click()
    device.wait(Until.hasObject(By.textContains("min")), 10_000)
    device.pressBack()
}

private const val SEARCH_HINT = "Para onde vais?"
