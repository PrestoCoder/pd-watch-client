const mysql = require('mysql2/promise');


exports.connectToDatabase = async () => {
  try{
    const connection = await mysql.createConnection({
      host: '127.0.0.1',
      user: 'root', // Replace with your MySQL username
      // password: 'chhibba123456', // Replace with your MySQL password
      database: 'db1'
    });
    return connection;
  } catch(error) {
    console.log(`Error occured while connecting to MySQL DB:- ${error}`)
  }  
}
  