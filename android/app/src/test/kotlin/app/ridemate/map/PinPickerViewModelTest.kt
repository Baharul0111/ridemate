package app.ridemate.map

import app.ridemate.core.model.LatLng
import app.ridemate.trip.FakeGeocoder
import app.ridemate.trip.FakeLocation
import app.ridemate.trip.LocationFix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PinPickerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val geocoder = FakeGeocoder(reverseLabel = "MG Road, Bengaluru")
    private val location = FakeLocation()
    private val a = LatLng(12.971601, 77.594612)
    private val b = LatLng(12.975123, 77.601987)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.picker(start: LatLng? = a, target: PinTarget = PinTarget.DROP): Pair<PinPickerViewModel, MutableList<PinEvent>> {
        val vm = PinPickerViewModel(
            PinPickerDeps(geocoder, location, pinnedLabel = { "Pinned location (${coordsText(it)})" }, debounceMs = 600),
            target,
            start,
        )
        val events = mutableListOf<PinEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        return vm to events
    }

    @Test
    fun `the address is looked up only after the map rests for the debounce`() = runTest(dispatcher) {
        val (vm, _) = picker()
        vm.onCameraIdle(b)
        advanceTimeBy(599)
        runCurrent()
        assertTrue(geocoder.reverseCalls.isEmpty())
        assertTrue(vm.state.value.resolving)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(b), geocoder.reverseCalls)
        assertEquals("MG Road, Bengaluru", vm.state.value.address)
        assertFalse(vm.state.value.resolving)
    }

    @Test
    fun `moving again within the debounce cancels the older lookup`() = runTest(dispatcher) {
        val (vm, _) = picker()
        vm.onCameraIdle(a.copy(lat = 12.9))
        advanceTimeBy(300)
        vm.onCameraMoveStarted()
        vm.onCameraIdle(b)
        advanceUntilIdle()
        assertEquals(listOf(b), geocoder.reverseCalls)
        assertEquals(b, vm.state.value.center)
    }

    @Test
    fun `confirm returns the exact pin coordinates with the address`() = runTest(dispatcher) {
        val (vm, _) = picker()
        vm.onCameraIdle(b)
        advanceUntilIdle()
        val place = vm.confirm()!!
        assertEquals(b, place.point)
        assertEquals(12.975123, place.point!!.lat, 0.0)
        assertEquals(77.601987, place.point!!.lng, 0.0)
        assertEquals("MG Road, Bengaluru", place.label)
    }

    @Test
    fun `no address still confirms the exact pin, labelled with its coordinates`() = runTest(dispatcher) {
        geocoder.reverseLabel = null
        val (vm, _) = picker()
        vm.onCameraIdle(b)
        advanceUntilIdle()
        assertTrue(vm.state.value.noAddress)
        assertNull(vm.state.value.address)
        val place = vm.confirm()!!
        assertEquals(b, place.point)
        assertEquals("Pinned location (12.97512, 77.60199)", place.label)
    }

    @Test
    fun `confirming before the lookup finishes uses the coordinates, never an older address`() = runTest(dispatcher) {
        val (vm, _) = picker()
        vm.onCameraIdle(a)
        advanceUntilIdle()
        vm.onCameraIdle(b)
        val place = vm.confirm()!!
        assertEquals(b, place.point)
        assertEquals("Pinned location (12.97512, 77.60199)", place.label)
    }

    @Test
    fun `without a start point the picker centres on my location when allowed`() = runTest(dispatcher) {
        val (vm, events) = picker(start = null)
        advanceUntilIdle()
        assertEquals(listOf<PinEvent>(PinEvent.MoveTo(FakeLocation.HERE)), events)
        assertEquals(FakeLocation.HERE, vm.state.value.center)
    }

    @Test
    fun `my location without permission asks for while in use location, denied shows a notice`() = runTest(dispatcher) {
        location.permission = false
        val (vm, events) = picker()
        vm.myLocation()
        runCurrent()
        assertEquals(listOf<PinEvent>(PinEvent.RequestLocationPermission), events)
        vm.onLocationPermissionResult(false)
        assertEquals(PinNotice.LOCATION_DENIED, vm.state.value.notice)
        assertEquals(0, location.calls)
    }

    @Test
    fun `no position found keeps the pin where it is`() = runTest(dispatcher) {
        location.fix = { LocationFix.Unavailable }
        val (vm, _) = picker()
        vm.myLocation()
        advanceUntilIdle()
        assertEquals(PinNotice.LOCATION_UNAVAILABLE, vm.state.value.notice)
        assertEquals(a, vm.state.value.center)
    }

    @Test
    fun `no camera position yet means nothing to confirm`() = runTest(dispatcher) {
        location.permission = false
        val (vm, _) = picker(start = null)
        assertFalse(vm.state.value.canConfirm)
        assertNull(vm.confirm())
    }
}
