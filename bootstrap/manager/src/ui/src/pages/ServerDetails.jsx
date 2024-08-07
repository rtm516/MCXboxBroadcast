import { DocumentCheckIcon, TrashIcon } from '@heroicons/react/16/solid'
import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import Input from '../components/basic/Input'
import Button from '../components/basic/Button'

import { formatTimestamp } from '../utils'
import { addNotification } from '../components/layout/NotificationContainer'
import { ExclamationTriangleIcon } from '@heroicons/react/24/outline'
import ConfirmModal from '../components/modals/ConfirmModal'

function ServerDetails () {
  const { serverId } = useParams()
  const navigate = useNavigate()

  const [info, setInfo] = useState({
    lastUpdated: null,
    hostname: '',
    port: 0,
    sessionInfo: {
      version: '',
      protocol: 0,
      players: 0,
      'host-name': '',
      'world-name': '',
      'max-players': 0
    }
  })

  const [formData, setFormData] = useState({
    hostname: '',
    port: 0
  })

  const [deleteOpen, setDeleteOpen] = useState(false)
  const deleteCallback = useRef(() => {})

  const updateData = () => {
    fetch('/api/servers/' + serverId).then((res) => {
      if (!res.ok) {
        // Redirect to the bots page if an error occurs
        addNotification('Error loading server: ' + res.statusText, 'red')
        return navigate('/servers')
      }
      return res.json()
    }).then((data) => {
      setInfo(data)
    })
  }

  useEffect(() => {
    updateData()
    const interval = setInterval(updateData, 2500) // Update every 2.5 seconds
    return () => clearInterval(interval)
  }, [])

  // Set the form data to the current hostname and port if it's empty
  useEffect(() => {
    if (formData.hostname === '' && formData.port === 0) {
      setFormData({
        hostname: info.hostname,
        port: info.port
      })
    }
  }, [info])

  const handleChange = (e) => {
    const { name, value } = e.target
    setFormData(prevState => ({
      ...prevState,
      [name]: value
    }))
  }

  const handleSubmit = (e) => {
    e.preventDefault()

    fetch('/api/servers/' + serverId, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(formData) }).then((res) => res.json()).then((data) => {
      if (data !== '') {
        return console.error(data)
      }
      updateData()
    })
  }

  const callDelete = () => {
    fetch('/api/servers/' + serverId, { method: 'DELETE' }).then((res) => res.json()).then((data) => {
      if (data.error) {
        console.error(data)
        addNotification('Failed to delete server: ' + data.error, 'red')
        return
      }
      addNotification('Deleted server', 'green')
      navigate('/servers')
    })
  }

  const formattedTimestamp = formatTimestamp(info.lastUpdated)

  return (
    <>
      <ConfirmModal
        title='Delete server'
        message='Are you sure you want to delete this server? This action cannot be undone.'
        confirmText='Delete'
        color='red'
        Icon={ExclamationTriangleIcon}
        open={deleteOpen}
        onClose={(success) => {
          setDeleteOpen(false)
          deleteCallback.current(success)
        }}
      />
      <div className='px-8 pb-12 flex items-center flex-col gap-5'>
        <div className='bg-white p-6 rounded shadow-lg max-w-6xl w-full'>
          <h3 className='text-3xl text-center pb-4'>Logs</h3>
          <div className='flex justify-between mb-4'>
            <div className='font-bold'>Last Updated:</div>
            <div title={formattedTimestamp.timestamp}> {formattedTimestamp.timestampAgo}</div>
          </div>
          <div className='flex justify-between mb-4'>
            <div className='font-bold'>MOTD 1:</div>
            <div>{info.sessionInfo['host-name']}</div>
          </div>
          <div className='flex justify-between mb-4'>
            <div className='font-bold'>MOTD 2:</div>
            <div>{info.sessionInfo['world-name']}</div>
          </div>
          <div className='flex justify-between mb-4'>
            <div className='font-bold'>Players:</div>
            <div>{info.sessionInfo.players}/{info.sessionInfo['max-players']}</div>
          </div>
          <div className='flex justify-between'>
            <div className='font-bold'>Version:</div>
            <div>{info.sessionInfo.version} ({info.sessionInfo.protocol})</div>
          </div>
        </div>
        <div className='bg-white p-6 rounded shadow-lg max-w-6xl w-full'>
          <h3 className='text-3xl text-center pb-4'>Settings</h3>
          <form className='flex flex-col gap-4' onSubmit={handleSubmit}>
            <Input
              label='Hostname'
              type='text'
              id='hostname'
              name='hostname'
              value={formData.hostname}
              onChange={handleChange}
              required
              placeholder='test.example.com'
            />
            <Input
              label='Port'
              type='number'
              id='port'
              name='port'
              value={formData.port}
              onChange={handleChange}
              required
              placeholder='19132'
              min='1'
              max='65535'
            />
            <Button color='green' type='submit'>
              <div>
                Save
              </div>
              <DocumentCheckIcon className='size-4' aria-hidden='true' />
            </Button>
            <Button
              color='red'
              onClick={() => {
                deleteCallback.current = (success) => {
                  if (!success) return
                  callDelete()
                }
                setDeleteOpen(true)
              }}
            >
              <div>
                Delete
              </div>
              <TrashIcon className='size-4' aria-hidden='true' />
            </Button>
          </form>
        </div>
      </div>
    </>
  )
}

export default ServerDetails
