import { createBrowserRouter, createHashRouter, RouterProvider } from 'react-router-dom'
import { Shell } from './app/Shell'
import { authEnabled } from './auth/session'
import { SignInWorkspace } from './auth/SignInWorkspace'
const makeRouter = import.meta.env.BASE_URL === '/' ? createBrowserRouter : createHashRouter
const router = makeRouter([{ path: '*', element: authEnabled ? <SignInWorkspace /> : <Shell /> }])
export default function App() {
  return <RouterProvider router={router} />
}
