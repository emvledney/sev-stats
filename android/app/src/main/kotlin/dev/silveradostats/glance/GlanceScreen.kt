package dev.silveradostats.glance

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.silveradostats.link.AppSettings
import dev.silveradostats.link.LinkConnection
import dev.silveradostats.link.LiveState
import dev.silveradostats.link.MonoClock
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The glance: a PaneTemplate with the title "Battery Glance", the link state as the header action,
 * and 4 rows (Capacity, Battery, Cells, Temps) whose titles never change, so that every update is a
 * free template refresh (see GlanceModel).
 *
 * The OBD link follows this screen's lifecycle: LinkConnection binds this app's LinkService when the
 * screen is created and lets go when it is destroyed, and the screen counts as a visible client only
 * between onStart and onStop (when the host shows it). The built-in session (no adapter) is sampled
 * for as long as the screen exists; its rows replace the OBD ones while OBD isn't delivering. Polling therefore runs only while the glance
 * (or the dashboard) is on screen. Updates: GlanceRefresh, at most one invalidate() a second,
 * only when the shown text would change, and never while the host still owes the previous template.
 */
class GlanceScreen(carContext: CarContext) : Screen(carContext) {
  private val settings = AppSettings.get(carContext)
  private var conn: LinkConnection? = null

  /** What the host last got from [onGetTemplate]. */
  private val host = HostTemplates()

  init {
    lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onCreate(owner: LifecycleOwner) {
          conn = LinkConnection(carContext, lifecycleScope)
        }

        override fun onStart(owner: LifecycleOwner) {
          conn?.setVisible(true)
        }

        override fun onStop(owner: LifecycleOwner) {
          conn?.setVisible(false)
        }

        override fun onDestroy(owner: LifecycleOwner) {
          conn?.close()
          conn = null
        }
      },
    )
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        val c = conn ?: return@repeatOnLifecycle
        GlanceRefresh.run(
          changes = merge(c.live, settings.state, c.session),
          current = ::current,
          host = host,
          invalidate = ::invalidate,
          clock = MonoClock::ms,
        )
      }
    }
  }

  private fun live(): LiveState = conn?.live?.value ?: LinkConnection.STARTING

  private fun current(): GlanceView {
    val s = settings.state.value
    return GlanceModel.build(live(), s.tempUnit, s.distUnit, MonoClock.ms(), conn?.session?.value)
  }

  override fun onGetTemplate(): Template {
    val v = current()
    host.onServed(v)
    return template(v)
  }

  private fun template(v: GlanceView): Template {
    val pane = Pane.Builder()
    for (r in v.rows) {
      val row = Row.Builder().setTitle(r.title)
      for (line in r.lines.take(GlanceModel.MAX_LINES)) row.addText(line)
      pane.addRow(row.build())
    }
    val status = Action.Builder()
      .setTitle(v.status)
      .setOnClickListener { onStatusTapped() }
      .build()
    val header = Header.Builder()
      .setStartHeaderAction(Action.APP_ICON)
      .setTitle(v.title)
      .addEndHeaderAction(status)
      .build()
    return PaneTemplate.Builder(pane.build()).setHeader(header).build()
  }

  /** The header action: reconnects when a retry is offered, else shows the full link message. */
  private fun onStatusTapped() {
    val live = live()
    val engine = conn?.service?.value?.engine
    if (live.canRetry && engine != null) {
      engine.retry()
      CarToast.makeText(carContext, "Reconnecting…", CarToast.LENGTH_SHORT).show()
    } else {
      CarToast.makeText(carContext, GlanceModel.detail(live), CarToast.LENGTH_LONG).show()
    }
  }
}
