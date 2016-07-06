package is.kow.scalatra-tracker-app

import org.scalatra._

class TrackerAppServlet extends ScalatraTrackerAppStack {

  get("/") {
    <html>
      <body>
        <h1>Hello, world!</h1>
        Say <a href="hello-scalate">hello to Scalate</a>.
      </body>
    </html>
  }

}
