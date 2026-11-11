package dev.silveradostats.diag

import android.Manifest
import dev.silveradostats.vehicle.CarPerm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One system dialog per list: a request must be able to stop between any two of them. */
class AppPermissionsTest {
  private val scan = Manifest.permission.BLUETOOTH_SCAN
  private val connect = Manifest.permission.BLUETOOTH_CONNECT

  @Test
  fun eachDialogIsOneGroup() {
    assertEquals(
      listOf(listOf(CarPerm.CAR_ENERGY), listOf(CarPerm.CAR_SPEED), listOf(scan, connect)),
      AppPermissions.dialogGroups(listOf(CarPerm.CAR_ENERGY, CarPerm.CAR_SPEED, scan, connect)),
    )
  }

  /** Android ignores a request for precise location alone, so the pair shares one dialog. */
  @Test
  fun theLocationPairIsOneDialog() {
    val fine = Manifest.permission.ACCESS_FINE_LOCATION
    val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
    assertEquals(listOf(listOf(CarPerm.CAR_SPEED), listOf(fine, coarse)), AppPermissions.dialogGroups(listOf(CarPerm.CAR_SPEED, fine, coarse)))
    assertTrue(AppPermissions.RUNTIME_REQUESTABLE.containsAll(listOf(fine, coarse)))
  }

  @Test
  fun everythingAskedForIsAskedForOnce() {
    val groups = AppPermissions.dialogGroups(AppPermissions.RUNTIME_REQUESTABLE)
    assertEquals(AppPermissions.RUNTIME_REQUESTABLE.toSet(), groups.flatten().toSet())
    assertEquals(AppPermissions.RUNTIME_REQUESTABLE.size, groups.flatten().size)
    assertEquals("car energy, car speed, nearby devices, location", 4, groups.size)
  }

  @Test
  fun onlyWhatIsMissing() {
    assertEquals(emptyList<List<String>>(), AppPermissions.dialogGroups(emptyList()))
    assertEquals(listOf(listOf(CarPerm.CAR_SPEED)), AppPermissions.dialogGroups(listOf(CarPerm.CAR_SPEED)))
    assertEquals(listOf(listOf(connect)), AppPermissions.dialogGroups(listOf(connect)))
  }
}
