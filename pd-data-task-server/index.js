const { connectUser, disconnectUser } = require('./connectUser');
const { connectToDatabase } = require('./dbConnect.js')
const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

const app = express();
const port = 9000;

let connection;

// Set up multer for file uploads
const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    const uploadDir = path.join(__dirname, 'uploads');
    if (!fs.existsSync(uploadDir)) {
      fs.mkdirSync(uploadDir);
    }
    cb(null, uploadDir);
  },
  filename: function (req, file, cb) {
    cb(null, file.originalname);
  }
});

const upload = multer({ storage: storage });

app.get('/validate_user/:userID', async (req, res) => {
  const currentUserId = req.params.userID;
  const previousUserId = req.query.previousUserId; // Get previous user ID from query parameters

  console.log("Validation request received for current user ID:", currentUserId);
  console.log("Previous user ID received:", previousUserId);

  let messageSent = "empty";
  let statusCode = 200;

  try {
    // If prevId = newId, no need to do anything
    if (currentUserId === previousUserId) {
      statusCode = 404;
      messageSent = "New user same as previous user => Doing nothing.";
    } else {
      // Attempt to connect the new user and disconnect the previous user
      const connectionResult = await connectUser(currentUserId, connection);
      const disconnectionResult = await disconnectUser(previousUserId, connection);

      // Success only if both users are connected/disconnected correctly
      if (connectionResult.result && disconnectionResult.result) {
        messageSent = `${connectionResult.status}, ${disconnectionResult.status}`;
        statusCode = 200;
      }

      // If connection failed, but disconnection happened, reconnect the previous user
      else if (!connectionResult.result && disconnectionResult.result) {
        await connectUser(previousUserId, connection);
        statusCode = 404;
        messageSent = connectionResult.status;
      }

      // If disconnection failed, but connection happened, disconnect the current user
      else if (!disconnectionResult.result && connectionResult.result) {
        await disconnectUser(currentUserId, connection);
        statusCode = 404;
        messageSent = disconnectionResult.status;
      }

      // If both failed, return a combined error message
      else {
        statusCode = 404;
        messageSent = `${connectionResult.status}, ${disconnectionResult.status}`;
      }
    }

    console.log(messageSent);

    let jsonMessage = {};
    if (statusCode === 200) {
      jsonMessage = {
        success: true,
        message: messageSent
      };
    } else if (statusCode === 404) {
      jsonMessage = {
        success: false,
        message: messageSent,
        error: messageSent
      };
    }

    res.status(statusCode).json(jsonMessage);

  } catch (error) {
    console.error("Error processing user validation:", error);
    res.status(500).json({
      success: false,
      message: "Internal server error",
      error: error.message
    });
  }
});

app.post('/parkinson/upload_sensor_data', upload.single('file'), (req, res) => {
  const { user_name, study_name, application_type, num_rows, start_timestamp, end_timestamp } = req.body;

  if (!req.file) {
    return res.status(400).send('No file uploaded.');
  }

  console.log('File uploaded successfully:', req.file.filename);
  res.status(200).send('File uploaded and data received successfully.');
});

app.use(express.json());


app.post('/api/save_pd_dairy', (req, res) => {
  // Extract data from the request body
  const { user_id, study_id, application_type, pd_dairy } = req.body;

  // Example: Log the received data (you can replace this with saving to a database)
  console.log("User ID:", user_id);
  console.log("Study ID:", study_id);
  console.log("Application Type:", application_type);
  console.log("PD Dairy Entries:", pd_dairy);

  // Define the directory to save the files
  const directoryPath = path.join(__dirname, 'saved_pd_dairy');

  // Create the directory if it doesn't exist
  if (!fs.existsSync(directoryPath)) {
    fs.mkdirSync(directoryPath);
  }

  // Define the filename using the user_id and a timestamp
  const timestamp = new Date().toISOString().replace(/[-T:.Z]/g, '');
  const filename = `${user_id}-${timestamp}.json`;

  // Define the full path to the file
  const filePath = path.join(directoryPath, filename);

  // Convert the request body to a JSON string
  const jsonData = JSON.stringify(req.body, null, 2);

  // Write the data to the file
  fs.writeFile(filePath, jsonData, (err) => {
    if (err) {
      console.error('Error saving PD Dairy data:', err);
      return res.status(500).json({
        message: "Failed to save PD Dairy data",
        status: "error"
      });
    }

    console.log('PD Dairy data saved successfully to', filePath);

    // Send a response back to the client
    res.status(201).json({
      message: "PD Dairy data saved successfully",
      status: "success"
    });
  });
});

app.post('/api/send_interaction_data', (req, res) => {
  const { userId, startTime, endTime, duration } = req.body;

  console.log(`User ${userId} started interaction at ${startTime} and ended at ${endTime}. Duration: ${duration} ms`);
  // Here, as per the requirement can store the data in db.

  res.status(201).json({
      message: "Interaction data saved successfully",
      status: "success"
  });
});


app.listen(port, async () => {
  try {
    connection = await connectToDatabase();
    console.log('Connected to the MySQL database');
    console.log(`Server is running on http://localhost:${port}`);
  } catch (err) {
    console.error('Error connecting to the database:', err.stack);
  }
});

