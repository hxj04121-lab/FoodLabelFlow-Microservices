import { createBrowserRouter, createHashRouter, RouterProvider } from 'react-router-dom'
import { Shell } from './app/Shell'
import { authEnabled } from './auth/session'
import { SignInWorkspace } from './auth/SignInWorkspace'
const makeRouter = import.meta.env.BASE_URL === '/' ? createBrowserRouter : createHashRouter
const localPreview = !authEnabled && ['localhost', '127.0.0.1', '[::1]'].includes(window.location.hostname)
const router = makeRouter([{ path: '*', element: localPreview ? <Shell /> : <SignInWorkspace /> }])
export default function App() {
  return <RouterProvider router={router} />
}
